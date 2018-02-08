/*
 *  Copyright 2018 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.c;

import com.carrotsearch.hppc.ObjectByteMap;
import com.carrotsearch.hppc.ObjectByteOpenHashMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.teavm.ast.decompilation.Decompiler;
import org.teavm.backend.c.analyze.CDependencyListener;
import org.teavm.backend.c.analyze.Characteristics;
import org.teavm.backend.c.analyze.StringPoolFiller;
import org.teavm.backend.c.analyze.TypeCollector;
import org.teavm.backend.c.generate.CallSiteGenerator;
import org.teavm.backend.c.generate.ClassGenerator;
import org.teavm.backend.c.generate.CodeWriter;
import org.teavm.backend.c.generate.GenerationContext;
import org.teavm.backend.c.generate.NameProvider;
import org.teavm.backend.c.generate.StringPool;
import org.teavm.backend.c.generate.StringPoolGenerator;
import org.teavm.backend.c.intrinsic.AddressIntrinsic;
import org.teavm.backend.c.intrinsic.AllocatorIntrinsic;
import org.teavm.backend.c.intrinsic.ExceptionHandlingIntrinsic;
import org.teavm.backend.c.intrinsic.FunctionIntrinsic;
import org.teavm.backend.c.intrinsic.GCIntrinsic;
import org.teavm.backend.c.intrinsic.Intrinsic;
import org.teavm.backend.c.intrinsic.MutatorIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformClassIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformClassMetadataIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformObjectIntrinsic;
import org.teavm.backend.c.intrinsic.ShadowStackIntrinsic;
import org.teavm.backend.c.intrinsic.StructureIntrinsic;
import org.teavm.dependency.ClassDependency;
import org.teavm.dependency.DependencyAnalyzer;
import org.teavm.dependency.DependencyListener;
import org.teavm.interop.Address;
import org.teavm.interop.PlatformMarkers;
import org.teavm.interop.Structure;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassReader;
import org.teavm.model.FieldReader;
import org.teavm.model.Instruction;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.ListableClassReaderSource;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.classes.TagRegistry;
import org.teavm.model.classes.VirtualTableProvider;
import org.teavm.model.instructions.CloneArrayInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.lowlevel.ClassInitializerEliminator;
import org.teavm.model.lowlevel.ClassInitializerTransformer;
import org.teavm.model.lowlevel.ShadowStackTransformer;
import org.teavm.model.transformation.ClassInitializerInsertionTransformer;
import org.teavm.model.transformation.ClassPatch;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.ExceptionHandling;
import org.teavm.runtime.RuntimeArray;
import org.teavm.runtime.RuntimeClass;
import org.teavm.runtime.RuntimeObject;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.TeaVMEntryPoint;
import org.teavm.vm.TeaVMTarget;
import org.teavm.vm.TeaVMTargetController;
import org.teavm.vm.spi.TeaVMHostExtension;

public class CTarget implements TeaVMTarget {
    private TeaVMTargetController controller;
    private ClassInitializerInsertionTransformer clinitInsertionTransformer;
    private ClassInitializerEliminator classInitializerEliminator;
    private ClassInitializerTransformer classInitializerTransformer;
    private ShadowStackTransformer shadowStackTransformer;
    private int minHeapSize = 32 * 1024 * 1024;

    public void setMinHeapSize(int minHeapSize) {
        this.minHeapSize = minHeapSize;
    }

    @Override
    public List<ClassHolderTransformer> getTransformers() {
        List<ClassHolderTransformer> transformers = new ArrayList<>();
        transformers.add(new ClassPatch());
        transformers.add(new CDependencyListener());
        return transformers;
    }

    @Override
    public List<DependencyListener> getDependencyListeners() {
        return Collections.singletonList(new CDependencyListener());
    }

    @Override
    public void setController(TeaVMTargetController controller) {
        this.controller = controller;
        classInitializerEliminator = new ClassInitializerEliminator(controller.getUnprocessedClassSource());
        classInitializerTransformer = new ClassInitializerTransformer();
        shadowStackTransformer = new ShadowStackTransformer(controller.getUnprocessedClassSource());
        clinitInsertionTransformer = new ClassInitializerInsertionTransformer(controller.getUnprocessedClassSource());
    }

    @Override
    public List<TeaVMHostExtension> getHostExtensions() {
        return Collections.emptyList();
    }

    @Override
    public boolean requiresRegisterAllocation() {
        return true;
    }

    @Override
    public void contributeDependencies(DependencyAnalyzer dependencyAnalyzer) {
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocate",
                RuntimeClass.class, Address.class), null).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocateArray",
                RuntimeClass.class, int.class, Address.class), null).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocateMultiArray",
                RuntimeClass.class, Address.class, int.class, RuntimeArray.class), null).use();

        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "<clinit>", void.class), null).use();

        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "throwException",
                Throwable.class, void.class), null).use();
        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "throwClassCastException",
                void.class), null).use();

        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "catchException",
                Throwable.class), null).use();

        dependencyAnalyzer.linkClass("java.lang.String", null);
        dependencyAnalyzer.linkClass("java.lang.Class", null);

        ClassDependency runtimeClassDep = dependencyAnalyzer.linkClass(RuntimeClass.class.getName(), null);
        ClassDependency runtimeObjectDep = dependencyAnalyzer.linkClass(RuntimeObject.class.getName(), null);
        ClassDependency runtimeArrayDep = dependencyAnalyzer.linkClass(RuntimeArray.class.getName(), null);
        for (ClassDependency classDep : Arrays.asList(runtimeClassDep, runtimeObjectDep, runtimeArrayDep)) {
            for (FieldReader field : classDep.getClassReader().getFields()) {
                dependencyAnalyzer.linkField(field.getReference(), null);
            }
        }
    }

    @Override
    public void afterOptimizations(Program program, MethodReader method, ListableClassReaderSource classSource) {
        clinitInsertionTransformer.apply(method, program);
        classInitializerEliminator.apply(program);
        classInitializerTransformer.transform(program);
        shadowStackTransformer.apply(program, method);
    }

    @Override
    public void emit(ListableClassHolderSource classes, BuildTarget buildTarget, String outputName) throws IOException {
        VirtualTableProvider vtableProvider = createVirtualTableProvider(classes);
        TagRegistry tagRegistry = new TagRegistry(classes);
        StringPool stringPool = new StringPool();

        Decompiler decompiler = new Decompiler(classes, controller.getClassLoader(), new HashSet<>(),
                new HashSet<>(), false);
        Characteristics characteristics = new Characteristics(controller.getUnprocessedClassSource());

        NameProvider nameProvider = new NameProvider(controller.getUnprocessedClassSource());

        List<Intrinsic> intrinsics = new ArrayList<>();
        intrinsics.add(new ShadowStackIntrinsic());
        intrinsics.add(new AddressIntrinsic());
        intrinsics.add(new AllocatorIntrinsic());
        intrinsics.add(new StructureIntrinsic(characteristics));
        intrinsics.add(new PlatformIntrinsic());
        intrinsics.add(new PlatformObjectIntrinsic());
        intrinsics.add(new PlatformClassIntrinsic());
        intrinsics.add(new PlatformClassMetadataIntrinsic());
        intrinsics.add(new GCIntrinsic());
        intrinsics.add(new MutatorIntrinsic());
        intrinsics.add(new ExceptionHandlingIntrinsic());
        intrinsics.add(new FunctionIntrinsic(characteristics));

        GenerationContext context = new GenerationContext(vtableProvider, characteristics, stringPool, nameProvider,
                controller.getDiagnostics(), classes, intrinsics);

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                buildTarget.createResource(outputName), "UTF-8"))) {
            CodeWriter codeWriter = new CodeWriter(writer);
            ClassGenerator classGenerator = new ClassGenerator(context, tagRegistry, decompiler, codeWriter);

            copyResource(codeWriter, "runtime.c");
            TypeCollector typeCollector = new TypeCollector();
            typeCollector.collect(classes);
            typeCollector.collectFromCallSites(shadowStackTransformer.getCallSites());
            StringPoolFiller stringPoolFiller = new StringPoolFiller(stringPool);
            stringPoolFiller.fillFrom(classes);
            stringPoolFiller.fillCallSites(shadowStackTransformer.getCallSites());
            for (ValueType type : typeCollector.getTypes()) {
                stringPool.getStringIndex(ClassGenerator.nameOfType(type));
            }

            generateClasses(classes, classGenerator, context, codeWriter, typeCollector);
            generateSpecialFunctions(context, codeWriter);
            copyResource(codeWriter, "runtime-epilogue.c");
            generateMain(context, codeWriter, classes, typeCollector);
        }
    }

    private void copyResource(CodeWriter writer, String resourceName) {
        ClassLoader classLoader = CTarget.class.getClassLoader();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                classLoader.getResourceAsStream("org/teavm/backend/c/" + resourceName)))) {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                writer.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void generateClasses(ListableClassHolderSource classes, ClassGenerator classGenerator,
            GenerationContext context, CodeWriter writer, TypeCollector typeCollector) {
        List<String> classNames = sortClassNames(classes);

        for (String className : classNames) {
            ClassHolder cls = classes.get(className);
            classGenerator.generateForwardDeclarations(cls);
        }

        for (String className : classNames) {
            ClassHolder cls = classes.get(className);
            classGenerator.generateStructures(cls);
        }

        for (String className : classNames) {
            classGenerator.generateVirtualTableStructures(classes.get(className));
        }

        new StringPoolGenerator(writer, context.getNames()).generate(context.getStringPool().getStrings());

        classGenerator.generateLayoutArray(classNames);

        for (ValueType type : typeCollector.getTypes()) {
            classGenerator.generateVirtualTableForwardDeclaration(type);
        }
        for (ValueType type : typeCollector.getTypes()) {
            classGenerator.generateVirtualTable(type, typeCollector.getTypes());
        }

        for (ValueType type : typeCollector.getTypes()) {
            classGenerator.generateIsSupertypeFunction(type);
        }

        classGenerator.generateStaticGCRoots(classNames);

        new CallSiteGenerator(context, writer).generate(shadowStackTransformer.getCallSites());

        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            classGenerator.generateClass(cls);
        }
    }

    private List<String> sortClassNames(ListableClassReaderSource classes) {
        List<String> classNames = new ArrayList<>(classes.getClassNames().size());
        Deque<String> stack = new ArrayDeque<>(classes.getClassNames());
        ObjectByteMap<String> stateMap = new ObjectByteOpenHashMap<>();

        while (!stack.isEmpty()) {
            String className = stack.pop();
            byte state = stateMap.getOrDefault(className, (byte) 0);
            switch (state) {
                case 0: {
                    stateMap.put(className, (byte) 1);
                    stack.push(className);
                    ClassReader cls = classes.get(className);
                    String parent = cls != null ? cls.getParent() : null;
                    if (parent == null) {
                        parent = RuntimeObject.class.getName();
                    }
                    if (!parent.equals(Structure.class.getName())
                            && stateMap.getOrDefault(cls.getParent(), (byte) 0) == 0) {
                        stack.push(parent);
                    }
                    break;
                }
                case 1:
                    stateMap.put(className, (byte) 2);
                    classNames.add(className);
                    break;
            }
        }

        return classNames;
    }

    private VirtualTableProvider createVirtualTableProvider(ListableClassHolderSource classes) {
        Set<MethodReference> virtualMethods = new HashSet<>();

        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            for (MethodHolder method : cls.getMethods()) {
                Program program = method.getProgram();
                if (program == null) {
                    continue;
                }
                for (int i = 0; i < program.basicBlockCount(); ++i) {
                    BasicBlock block = program.basicBlockAt(i);
                    for (Instruction insn : block) {
                        if (insn instanceof InvokeInstruction) {
                            InvokeInstruction invoke = (InvokeInstruction) insn;
                            if (invoke.getType() == InvocationType.VIRTUAL) {
                                virtualMethods.add(invoke.getMethod());
                            }
                        } else if (insn instanceof CloneArrayInstruction) {
                            virtualMethods.add(new MethodReference(Object.class, "clone", Object.class));
                        }
                    }
                }
            }
        }

        return new VirtualTableProvider(classes, virtualMethods);
    }

    private void generateSpecialFunctions(GenerationContext context, CodeWriter writer) {
        generateThrowCCE(context, writer);
    }

    private void generateThrowCCE(GenerationContext context, CodeWriter writer) {
        writer.println("static void* throwClassCastException() {").indent();
        String methodName = context.getNames().forMethod(new MethodReference(ExceptionHandling.class,
                "throwClassCastException", void.class));
        writer.println(methodName + "();");
        writer.println("return NULL;");
        writer.outdent().println("}");
    }

    private void generateMain(GenerationContext context, CodeWriter writer, ListableClassHolderSource classes,
            TypeCollector types) {
        writer.println("int main(int argc, char** argv) {").indent();

        writer.println("initHeap(" + minHeapSize + ");");
        generateVirtualTableHeaders(context, writer, types);
        generateStringPoolHeaders(context, writer);
        generateStaticInitializerCalls(context, writer, classes);
        generateCallToMainMethod(context, writer);

        writer.outdent().println("}");
    }

    private void generateStaticInitializerCalls(GenerationContext context, CodeWriter writer,
            ListableClassReaderSource classes) {
        MethodDescriptor clinitDescriptor = new MethodDescriptor("<clinit>", ValueType.VOID);
        for (String className : classes.getClassNames()) {
            ClassReader cls = classes.get(className);
            if (!context.getCharacteristics().isStaticInit(cls.getName())
                    && !context.getCharacteristics().isStructure(cls.getName())) {
                continue;
            }


            if (cls.getMethod(clinitDescriptor) == null) {
                continue;
            }

            String clinitName = context.getNames().forMethod(new MethodReference(className, clinitDescriptor));
            writer.println(clinitName + "();");
        }
    }

    private void generateVirtualTableHeaders(GenerationContext context, CodeWriter writer,
            TypeCollector typeCollector) {
        String classClassName = context.getNames().forClassInstance(ValueType.object("java.lang.Class"));
        writer.println("int32_t classHeader = PACK_CLASS(&" + classClassName + ") | " + RuntimeObject.GC_MARKED + ";");

        for (ValueType type : typeCollector.getTypes()) {
            if (!ClassGenerator.needsVirtualTable(context.getCharacteristics(), type)) {
                continue;
            }

            String typeName = context.getNames().forClassInstance(type);
            writer.println("((JavaObject*) &" + typeName + ")->header = classHeader;");
        }
    }

    private void generateStringPoolHeaders(GenerationContext context, CodeWriter writer) {
        String stringClassName = context.getNames().forClassInstance(ValueType.object("java.lang.String"));
        writer.println("int32_t stringHeader = PACK_CLASS(&" + stringClassName + ") | "
                + RuntimeObject.GC_MARKED + ";");

        int size = context.getStringPool().getStrings().size();
        writer.println("for (int i = 0; i < " + size + "; ++i) {").indent();
        writer.println("((JavaObject*) (stringPool + i))->header = stringHeader;");
        writer.outdent().println("}");
    }

    private void generateCallToMainMethod(GenerationContext context, CodeWriter writer) {
        TeaVMEntryPoint entryPoint = controller.getEntryPoints().get("main");
        if (entryPoint != null) {
            String mainMethod = context.getNames().forMethod(entryPoint.getReference());
            writer.println(mainMethod + "(NULL);");
        }
    }

    @Override
    public String[] getPlatformTags() {
        return new String[] { PlatformMarkers.C, PlatformMarkers.WEBASSEMBLY };
    }
}
