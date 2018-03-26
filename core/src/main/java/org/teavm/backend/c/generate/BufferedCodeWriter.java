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
package org.teavm.backend.c.generate;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class BufferedCodeWriter extends CodeWriter {
    private List<Fragment> fragments = new ArrayList<>();
    private int currentIndent;
    private int lastIndent;
    private StringBuilder buffer = new StringBuilder();
    private boolean isNewLine;

    public BufferedCodeWriter() {
    }

    public void writeTo(PrintWriter writer) {
        for (Fragment fragment : fragments) {
            fragment.writeTo(writer);
        }
    }

    @Override
    public CodeWriter fragment() {
        fragments.add(new SimpleFragment(isNewLine, currentIndent, buffer.toString()));
        buffer.setLength(0);
        isNewLine = false;

        BufferedCodeWriter innerWriter = new BufferedCodeWriter();
        innerWriter.currentIndent = currentIndent;
        fragments.add(new InnerWriterFragment(innerWriter.fragments));
        return innerWriter;
    }

    @Override
    protected void newLine() {
        fragments.add(new SimpleFragment(isNewLine, lastIndent, buffer.toString()));
        buffer.setLength(0);
        isNewLine = true;
        lastIndent = currentIndent;
    }

    @Override
    protected void append(String text) {
        buffer.append(text);
    }

    @Override
    protected void indentBy(int amount) {
        currentIndent += amount;
    }

    @Override
    public void flush() {
        fragments.add(new SimpleFragment(isNewLine, lastIndent, buffer.toString()));
    }

    static abstract class Fragment {
        abstract void writeTo(PrintWriter writer);
    }

    static class SimpleFragment extends Fragment {
        boolean newLine;
        int indentLevel;
        String text;

        SimpleFragment(boolean newLine, int indentLevel, String text) {
            this.newLine = newLine;
            this.indentLevel = indentLevel;
            this.text = text;
        }

        @Override
        void writeTo(PrintWriter writer) {
            if (newLine) {
                writer.println();
                for (int i = 0; i < indentLevel; ++i) {
                    writer.append("    ");
                }
            }
            writer.append(text);
        }
    }

    static class InnerWriterFragment extends Fragment {
        List<Fragment> fragments;

        InnerWriterFragment(List<Fragment> fragments) {
            this.fragments = fragments;
        }

        @Override
        void writeTo(PrintWriter writer) {
            for (Fragment fragment : fragments) {
                fragment.writeTo(writer);
            }
        }
    }
}
