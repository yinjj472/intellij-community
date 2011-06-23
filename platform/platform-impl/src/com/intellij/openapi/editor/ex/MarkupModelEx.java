/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.editor.ex;

import com.intellij.openapi.editor.impl.event.MarkupModelListener;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.util.Consumer;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Iterator;

/**
 * @author max
 */
public interface MarkupModelEx extends MarkupModel {
  void dispose();

  @Nullable
  RangeHighlighter addPersistentLineHighlighter(int lineNumber, int layer, TextAttributes textAttributes);
  boolean containsHighlighter(@NotNull RangeHighlighter highlighter);

  void addMarkupModelListener(@NotNull MarkupModelListener listener);
  void removeMarkupModelListener(@NotNull MarkupModelListener listener);

  void setRangeHighlighterAttributes(@NotNull RangeHighlighter highlighter, TextAttributes textAttributes);

  boolean processHighlightsOverlappingWith(int start, int end, @NotNull Processor<? super RangeHighlighterEx> processor);

  @NotNull
  Iterator<RangeHighlighterEx> overlappingIterator(int startOffset, int endOffset);

  // optimization: creates highlighter and fires only one event: highlighterCreated
  RangeHighlighterEx addRangeHighlighterAndChangeAttributes(int startOffset,
                                                            int endOffset,
                                                            int layer,
                                                            TextAttributes textAttributes,
                                                            @NotNull HighlighterTargetArea targetArea,
                                                            boolean isPersistent,
                                                            Consumer<RangeHighlighterEx> changeAttributesAction);

  // runs change attributes action and fires highlighterChanged event if there were changes
  void changeAttributesInBatch(@NotNull RangeHighlighterEx highlighter, @NotNull Consumer<RangeHighlighterEx> changeAttributesAction);

  interface SweepProcessor<T> {
    boolean process(int offset, T interval, boolean atStart, Collection<T> overlappingIntervals);
  }

  boolean sweep(int start, int end, @NotNull final SweepProcessor<RangeHighlighterEx> sweepProcessor);
}
