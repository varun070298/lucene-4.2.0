package org.apache.lucene.search.postingshighlight;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.index.Term;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.SorterTemplate;

/**
 * Represents a passage (typically a sentence of the document). 
 * <p>
 * A passage contains {@link #getNumMatches} highlights from the query,
 * and the offsets and query terms that correspond with each match.
 * @lucene.experimental
 */
public final class Passage {
  int startOffset = -1;
  int endOffset = -1;
  float score = 0.0f;

  int matchStarts[] = new int[8];
  int matchEnds[] = new int[8];
  Term matchTerms[] = new Term[8];
  int numMatches = 0;
  
  void addMatch(int startOffset, int endOffset, Term term) {
    assert startOffset >= this.startOffset && startOffset <= this.endOffset;
    if (numMatches == matchStarts.length) {
      matchStarts = ArrayUtil.grow(matchStarts, numMatches+1);
      matchEnds = ArrayUtil.grow(matchEnds, numMatches+1);
      Term newMatchTerms[] = new Term[ArrayUtil.oversize(numMatches+1, RamUsageEstimator.NUM_BYTES_OBJECT_REF)];
      System.arraycopy(matchTerms, 0, newMatchTerms, 0, numMatches);
      matchTerms = newMatchTerms;
    }
    matchStarts[numMatches] = startOffset;
    matchEnds[numMatches] = endOffset;
    matchTerms[numMatches] = term;
    numMatches++;
  }
  
  void sort() {
    final int starts[] = matchStarts;
    final int ends[] = matchEnds;
    final Term terms[] = matchTerms;
    new SorterTemplate() {
      @Override
      protected void swap(int i, int j) {
        int temp = starts[i];
        starts[i] = starts[j];
        starts[j] = temp;
        
        temp = ends[i];
        ends[i] = ends[j];
        ends[j] = temp;
        
        Term tempTerm = terms[i];
        terms[i] = terms[j];
        terms[j] = tempTerm;
      }

      @Override
      protected int compare(int i, int j) {
        // TODO: java7 use Integer.compare(starts[i], starts[j])
        return Long.signum(((long)starts[i]) - starts[j]);
      }

      @Override
      protected void setPivot(int i) {
        pivot = starts[i];
      }

      @Override
      protected int comparePivot(int j) {
        // TODO: java7 use Integer.compare(pivot, starts[j])
        return Long.signum(((long)pivot) - starts[j]);
      }
      
      int pivot;
    }.mergeSort(0, numMatches-1);
  }
  
  void reset() {
    startOffset = endOffset = -1;
    score = 0.0f;
    numMatches = 0;
  }

  /**
   * Start offset of this passage.
   * @return start index (inclusive) of the passage in the 
   *         original content: always &gt;= 0.
   */
  public int getStartOffset() {
    return startOffset;
  }

  /**
   * End offset of this passage.
   * @return end index (exclusive) of the passage in the 
   *         original content: always &gt;= {@link #getStartOffset()}
   */
  public int getEndOffset() {
    return endOffset;
  }

  /**
   * Passage's score.
   */
  public float getScore() {
    return score;
  }
  
  /**
   * Number of term matches available in 
   * {@link #getMatchStarts}, {@link #getMatchEnds}, 
   * {@link #getMatchTerms}
   */
  public int getNumMatches() {
    return numMatches;
  }

  /**
   * Start offsets of the term matches, in increasing order.
   * <p>
   * Only {@link #getNumMatches} are valid. Note that these
   * offsets are absolute (not relative to {@link #getStartOffset()}).
   */
  public int[] getMatchStarts() {
    return matchStarts;
  }

  /**
   * End offsets of the term matches, corresponding with {@link #getMatchStarts}. 
   * <p>
   * Only {@link #getNumMatches} are valid. Note that its possible that an end offset 
   * could exceed beyond the bounds of the passage ({@link #getEndOffset()}), if the 
   * Analyzer produced a term which spans a passage boundary.
   */
  public int[] getMatchEnds() {
    return matchEnds;
  }

  /**
   * Term of the matches, corresponding with {@link #getMatchStarts()}.
   * <p>
   * Only {@link #getNumMatches()} are valid.
   */
  public Term[] getMatchTerms() {
    return matchTerms;
  }
}
