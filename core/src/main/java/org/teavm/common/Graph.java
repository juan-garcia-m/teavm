/*
 *  Copyright 2011 Alexey Andreev.
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
package org.teavm.common;

/**
 *
 * @author Alexey Andreev
 */
public interface Graph {
    int size();

    int[] incomingEdges(int node);

    int copyIncomingEdges(int node, int[] target);

    int[] outgoingEdges(int node);

    int copyOutgoingEdges(int node, int[] target);

    int incomingEdgesCount(int node);

    int outgoingEdgesCount(int node);
}