package org.apache.lucene.facet.search;

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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetTestCase;
import org.apache.lucene.facet.index.FacetFields;
import org.apache.lucene.facet.params.FacetIndexingParams;
import org.apache.lucene.facet.params.FacetSearchParams;
import org.apache.lucene.facet.search.DrillSideways.DrillSidewaysResult;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util._TestUtil;

public class TestDrillSideways extends FacetTestCase {

  private DirectoryTaxonomyWriter taxoWriter;
  private RandomIndexWriter writer;
  private FacetFields facetFields;

  private void add(String ... categoryPaths) throws IOException {
    Document doc = new Document();
    List<CategoryPath> paths = new ArrayList<CategoryPath>();
    for(String categoryPath : categoryPaths) {
      paths.add(new CategoryPath(categoryPath, '/'));
    }
    facetFields.addFields(doc, paths);
    writer.addDocument(doc);
  }

  public void testBasic() throws Exception {
    Directory dir = newDirectory();
    Directory taxoDir = newDirectory();
    writer = new RandomIndexWriter(random(), dir);

    // Writes facet ords to a separate directory from the
    // main index:
    taxoWriter = new DirectoryTaxonomyWriter(taxoDir, IndexWriterConfig.OpenMode.CREATE);

    // Reused across documents, to add the necessary facet
    // fields:
    facetFields = new FacetFields(taxoWriter);

    add("Author/Bob", "Publish Date/2010/10/15");
    add("Author/Lisa", "Publish Date/2010/10/20");
    add("Author/Lisa", "Publish Date/2012/1/1");
    add("Author/Susan", "Publish Date/2012/1/7");
    add("Author/Frank", "Publish Date/1999/5/5");

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());
    writer.close();

    //System.out.println("searcher=" + searcher);

    // NRT open
    TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxoWriter);
    taxoWriter.close();

    // Count both "Publish Date" and "Author" dimensions, in
    // drill-down:
    FacetSearchParams fsp = new FacetSearchParams(
        new CountFacetRequest(new CategoryPath("Publish Date"), 10), 
        new CountFacetRequest(new CategoryPath("Author"), 10));

    // Simple case: drill-down on a single field; in this
    // case the drill-sideways + drill-down counts ==
    // drill-down of just the query: 
    DrillDownQuery ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());
    ddq.add(new CategoryPath("Author", "Lisa"));
    DrillSidewaysResult r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);

    assertEquals(2, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is only drill-down, and Lisa published
    // one in 2012 and one in 2010:
    assertEquals("Publish Date: 2012=1 2010=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down: Lisa
    // (drill-down) published twice, and Frank/Susan/Bob
    // published once:
    assertEquals("Author: Lisa=2 Frank=1 Susan=1 Bob=1", toString(r.facetResults.get(1)));

    // Same simple case, but no baseQuery (pure browse):
    // drill-down on a single field; in this case the
    // drill-sideways + drill-down counts == drill-down of
    // just the query:
    ddq = new DrillDownQuery(fsp.indexingParams);
    ddq.add(new CategoryPath("Author", "Lisa"));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);

    assertEquals(2, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is only drill-down, and Lisa published
    // one in 2012 and one in 2010:
    assertEquals("Publish Date: 2012=1 2010=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down: Lisa
    // (drill-down) published twice, and Frank/Susan/Bob
    // published once:
    assertEquals("Author: Lisa=2 Frank=1 Susan=1 Bob=1", toString(r.facetResults.get(1)));

    // Another simple case: drill-down on on single fields
    // but OR of two values
    ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());
    ddq.add(new CategoryPath("Author", "Lisa"), new CategoryPath("Author", "Bob"));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);
    assertEquals(3, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is only drill-down: Lisa and Bob
    // (drill-down) published twice in 2010 and once in 2012:
    assertEquals("Publish Date: 2010=2 2012=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down: Lisa
    // (drill-down) published twice, and Frank/Susan/Bob
    // published once:
    assertEquals("Author: Lisa=2 Frank=1 Susan=1 Bob=1", toString(r.facetResults.get(1)));

    // More interesting case: drill-down on two fields
    ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());
    ddq.add(new CategoryPath("Author", "Lisa"));
    ddq.add(new CategoryPath("Publish Date", "2010"));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);
    assertEquals(1, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is drill-sideways + drill-down: Lisa
    // (drill-down) published once in 2010 and once in 2012:
    assertEquals("Publish Date: 2012=1 2010=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down:
    // only Lisa & Bob published (once each) in 2010:
    assertEquals("Author: Lisa=1 Bob=1", toString(r.facetResults.get(1)));

    // Even more interesting case: drill down on two fields,
    // but one of them is OR
    ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());

    // Drill down on Lisa or Bob:
    ddq.add(new CategoryPath("Author", "Lisa"),
            new CategoryPath("Author", "Bob"));
    ddq.add(new CategoryPath("Publish Date", "2010"));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);
    assertEquals(2, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is both drill-sideways + drill-down:
    // Lisa or Bob published twice in 2010 and once in 2012:
    assertEquals("Publish Date: 2010=2 2012=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down:
    // only Lisa & Bob published (once each) in 2010:
    assertEquals("Author: Lisa=1 Bob=1", toString(r.facetResults.get(1)));

    // Test drilling down on invalid field:
    ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());
    ddq.add(new CategoryPath("Foobar", "Baz"));
    fsp = new FacetSearchParams(
        new CountFacetRequest(new CategoryPath("Publish Date"), 10), 
        new CountFacetRequest(new CategoryPath("Foobar"), 10));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);
    assertEquals(0, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    assertEquals("Publish Date:", toString(r.facetResults.get(0)));
    assertEquals("Foobar:", toString(r.facetResults.get(1)));

    // Test drilling down on valid term or'd with invalid term:
    ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());
    ddq.add(new CategoryPath("Author", "Lisa"),
            new CategoryPath("Author", "Tom"));
    fsp = new FacetSearchParams(
        new CountFacetRequest(new CategoryPath("Publish Date"), 10), 
        new CountFacetRequest(new CategoryPath("Author"), 10));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);
    assertEquals(2, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is only drill-down, and Lisa published
    // one in 2012 and one in 2010:
    assertEquals("Publish Date: 2012=1 2010=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down: Lisa
    // (drill-down) published twice, and Frank/Susan/Bob
    // published once:
    assertEquals("Author: Lisa=2 Frank=1 Susan=1 Bob=1", toString(r.facetResults.get(1)));

    // Test main query gets null scorer:
    fsp = new FacetSearchParams(
        new CountFacetRequest(new CategoryPath("Publish Date"), 10), 
        new CountFacetRequest(new CategoryPath("Author"), 10));
    ddq = new DrillDownQuery(fsp.indexingParams, new TermQuery(new Term("foobar", "baz")));
    ddq.add(new CategoryPath("Author", "Lisa"));
    r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);

    assertEquals(0, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    assertEquals("Publish Date:", toString(r.facetResults.get(0)));
    assertEquals("Author:", toString(r.facetResults.get(1)));

    searcher.getIndexReader().close();
    taxoReader.close();
    dir.close();
    taxoDir.close();
  }

  public void testSometimesInvalidDrillDown() throws Exception {
    Directory dir = newDirectory();
    Directory taxoDir = newDirectory();
    writer = new RandomIndexWriter(random(), dir);

    // Writes facet ords to a separate directory from the
    // main index:
    taxoWriter = new DirectoryTaxonomyWriter(taxoDir, IndexWriterConfig.OpenMode.CREATE);

    // Reused across documents, to add the necessary facet
    // fields:
    facetFields = new FacetFields(taxoWriter);

    add("Author/Bob", "Publish Date/2010/10/15");
    add("Author/Lisa", "Publish Date/2010/10/20");
    writer.commit();
    // 2nd segment has no Author:
    add("Foobar/Lisa", "Publish Date/2012/1/1");

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());
    writer.close();

    //System.out.println("searcher=" + searcher);

    // NRT open
    TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxoWriter);
    taxoWriter.close();

    // Count both "Publish Date" and "Author" dimensions, in
    // drill-down:
    FacetSearchParams fsp = new FacetSearchParams(
        new CountFacetRequest(new CategoryPath("Publish Date"), 10), 
        new CountFacetRequest(new CategoryPath("Author"), 10));

    DrillDownQuery ddq = new DrillDownQuery(fsp.indexingParams, new MatchAllDocsQuery());
    ddq.add(new CategoryPath("Author", "Lisa"));
    DrillSidewaysResult r = new DrillSideways(searcher, taxoReader).search(null, ddq, 10, fsp);

    assertEquals(1, r.hits.totalHits);
    assertEquals(2, r.facetResults.size());
    // Publish Date is only drill-down, and Lisa published
    // one in 2012 and one in 2010:
    assertEquals("Publish Date: 2010=1", toString(r.facetResults.get(0)));
    // Author is drill-sideways + drill-down: Lisa
    // (drill-down) published once, and Bob
    // published once:
    assertEquals("Author: Lisa=1 Bob=1", toString(r.facetResults.get(1)));

    searcher.getIndexReader().close();
    taxoReader.close();
    dir.close();
    taxoDir.close();
  }

  private static class Doc implements Comparable<Doc> {
    String id;
    String contentToken;

    // -1 if the doc is missing this dim, else the index
    // -into the values for this dim:
    int[] dims;

    // 2nd value per dim for the doc (so we test
    // multi-valued fields):
    int[] dims2;
    boolean deleted;

    @Override
    public int compareTo(Doc other) {
      return id.compareTo(other.id);
    }
  }

  private double aChance, bChance, cChance;

  private String randomContentToken(boolean isQuery) {
    double d = random().nextDouble();
    if (isQuery) {
      if (d < 0.33) {
        return "a";
      } else if (d < 0.66) {
        return "b";
      } else {
        return "c";
      }
    } else {
      if (d <= aChance) {
        return "a";
      } else if (d < aChance + bChance) {
        return "b";
      } else {
        return "c";
      }
    }
  }

  public void testRandom() throws Exception {

    while (aChance == 0.0) {
      aChance = random().nextDouble();
    }
    while (bChance == 0.0) {
      bChance = random().nextDouble();
    }
    while (cChance == 0.0) {
      cChance = random().nextDouble();
    }
    /*
    aChance = .01;
    bChance = 0.5;
    cChance = 1.0;
    */
    double sum = aChance + bChance + cChance;
    aChance /= sum;
    bChance /= sum;
    cChance /= sum;

    int numDims = _TestUtil.nextInt(random(), 2, 5);
    //int numDims = 3;
    int numDocs = atLeast(3000);
    //int numDocs = 20;
    if (VERBOSE) {
      System.out.println("numDims=" + numDims + " numDocs=" + numDocs + " aChance=" + aChance + " bChance=" + bChance + " cChance=" + cChance);
    }
    String[][] dimValues = new String[numDims][];
    int valueCount = 2;
    for(int dim=0;dim<numDims;dim++) {
      Set<String> values = new HashSet<String>();
      while (values.size() < valueCount) {
        String s;
        while (true) {
          s = _TestUtil.randomRealisticUnicodeString(random());
          // We cannot include this character else the label
          // is silently truncated:
          if (s.indexOf(FacetIndexingParams.DEFAULT_FACET_DELIM_CHAR) == -1) {
            break;
          }
        }
        //String s = _TestUtil.randomSimpleString(random());
        if (s.length() > 0) {
          values.add(s);
        }
      } 
      dimValues[dim] = values.toArray(new String[values.size()]);
      valueCount *= 2;
    }

    List<Doc> docs = new ArrayList<Doc>();
    for(int i=0;i<numDocs;i++) {
      Doc doc = new Doc();
      doc.id = ""+i;
      doc.contentToken = randomContentToken(false);
      doc.dims = new int[numDims];
      doc.dims2 = new int[numDims];
      for(int dim=0;dim<numDims;dim++) {
        if (random().nextInt(5) == 3) {
          // This doc is missing this dim:
          doc.dims[dim] = -1;
        } else if (dimValues[dim].length <= 4) {
          int dimUpto = 0;
          doc.dims[dim] = dimValues[dim].length-1;
          while (dimUpto < dimValues[dim].length) {
            if (random().nextBoolean()) {
              doc.dims[dim] = dimUpto;
              break;
            }
            dimUpto++;
          }
        } else {
          doc.dims[dim] = random().nextInt(dimValues[dim].length);
        }

        if (random().nextInt(5) == 3) {
          // 2nd value:
          doc.dims2[dim] = random().nextInt(dimValues[dim].length);
        } else {
          doc.dims2[dim] = -1;
        }
      }
      docs.add(doc);
    }

    Directory d = newDirectory();
    Directory td = newDirectory();

    IndexWriterConfig iwc = newIndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random()));
    iwc.setInfoStream(InfoStream.NO_OUTPUT);
    RandomIndexWriter w = new RandomIndexWriter(random(), d, iwc);
    DirectoryTaxonomyWriter tw = new DirectoryTaxonomyWriter(td, IndexWriterConfig.OpenMode.CREATE);
    facetFields = new FacetFields(tw);

    for(Doc rawDoc : docs) {
      Document doc = new Document();
      doc.add(newStringField("id", rawDoc.id, Field.Store.YES));
      doc.add(newStringField("content", rawDoc.contentToken, Field.Store.NO));
      List<CategoryPath> paths = new ArrayList<CategoryPath>();

      if (VERBOSE) {
        System.out.println("  doc id=" + rawDoc.id + " token=" + rawDoc.contentToken);
      }
      for(int dim=0;dim<numDims;dim++) {
        int dimValue = rawDoc.dims[dim];
        if (dimValue != -1) {
          paths.add(new CategoryPath("dim" + dim, dimValues[dim][dimValue]));
          doc.add(new StringField("dim" + dim, dimValues[dim][dimValue], Field.Store.YES));
          if (VERBOSE) {
            System.out.println("    dim" + dim + "=" + new BytesRef(dimValues[dim][dimValue]));
          }
        }
        int dimValue2 = rawDoc.dims2[dim];
        if (dimValue2 != -1) {
          paths.add(new CategoryPath("dim" + dim, dimValues[dim][dimValue2]));
          doc.add(new StringField("dim" + dim, dimValues[dim][dimValue2], Field.Store.YES));
          if (VERBOSE) {
            System.out.println("      dim" + dim + "=" + new BytesRef(dimValues[dim][dimValue2]));
          }
        }
      }
      if (!paths.isEmpty()) {
        facetFields.addFields(doc, paths);
      }
      w.addDocument(doc);
    }

    if (random().nextBoolean()) {
      // Randomly delete a few docs:
      int numDel = _TestUtil.nextInt(random(), 1, (int) (numDocs*0.05));
      if (VERBOSE) {
        System.out.println("delete " + numDel);
      }
      int delCount = 0;
      while (delCount < numDel) {
        Doc doc = docs.get(random().nextInt(docs.size()));
        if (!doc.deleted) {
          if (VERBOSE) {
            System.out.println("  delete id=" + doc.id);
          }
          doc.deleted = true;
          w.deleteDocuments(new Term("id", doc.id));
          delCount++;
        }
      }
    }

    if (random().nextBoolean()) {
      if (VERBOSE) {
        System.out.println("TEST: forceMerge(1)...");
      }
      w.forceMerge(1);
    }
    IndexReader r = w.getReader();
    w.close();
    if (VERBOSE) {
      System.out.println("r.numDocs() = " + r.numDocs());
    }

    // NRT open
    TaxonomyReader tr = new DirectoryTaxonomyReader(tw);
    tw.close();

    List<FacetRequest> requests = new ArrayList<FacetRequest>();
    for(int i=0;i<numDims;i++) {
      requests.add(new CountFacetRequest(new CategoryPath("dim" + i), dimValues[numDims-1].length));
    }

    FacetSearchParams fsp = new FacetSearchParams(requests);
    IndexSearcher s = new IndexSearcher(r);

    int numIters = atLeast(10);

    for(int iter=0;iter<numIters;iter++) {
      String contentToken = random().nextInt(30) == 17 ? null : randomContentToken(true);
      int numDrillDown = _TestUtil.nextInt(random(), 1, Math.min(4, numDims));
      String[][] drillDowns = new String[numDims][];
      if (VERBOSE) {
        System.out.println("\nTEST: iter=" + iter + " baseQuery=" + contentToken + " numDrillDown=" + numDrillDown);
      }
      int count = 0;
      while (count < numDrillDown) {
        int dim = random().nextInt(numDims);
        if (drillDowns[dim] == null) {
          if (random().nextBoolean()) {
            // Drill down on one value:
            drillDowns[dim] = new String[] {dimValues[dim][random().nextInt(dimValues[dim].length)]};
          } else {
            int orCount = _TestUtil.nextInt(random(), 1, Math.min(5, dimValues[dim].length));
            drillDowns[dim] = new String[orCount];
            for(int i=0;i<orCount;i++) {
              while (true) {
                String value = dimValues[dim][random().nextInt(dimValues[dim].length)];
                for(int j=0;j<i;j++) {
                  if (value.equals(drillDowns[dim][j])) {
                    value = null;
                    break;
                  }
                }
                if (value != null) {
                  drillDowns[dim][i] = value;
                  break;
                }
              }
            }
          }
          if (VERBOSE) {
            BytesRef[] values = new BytesRef[drillDowns[dim].length];
            for(int i=0;i<values.length;i++) {
              values[i] = new BytesRef(drillDowns[dim][i]);
            }
            System.out.println("  dim" + dim + "=" + Arrays.toString(values));
          }
          count++;
        }
      }

      Query baseQuery;
      if (contentToken == null) {
        baseQuery = new MatchAllDocsQuery();
      } else {
        baseQuery = new TermQuery(new Term("content", contentToken));
      }

      DrillDownQuery ddq = new DrillDownQuery(fsp.indexingParams, baseQuery);

      for(int dim=0;dim<numDims;dim++) {
        if (drillDowns[dim] != null) {
          CategoryPath[] paths = new CategoryPath[drillDowns[dim].length];
          int upto = 0;
          for(String value : drillDowns[dim]) {
            paths[upto++] = new CategoryPath("dim" + dim, value);
          }
          ddq.add(paths);
        }
      }

      Filter filter;
      if (random().nextInt(7) == 6) {
        if (VERBOSE) {
          System.out.println("  only-even filter");
        }
        filter = new Filter() {
            @Override
            public DocIdSet getDocIdSet(AtomicReaderContext context, Bits acceptDocs) throws IOException {
              int maxDoc = context.reader().maxDoc();
              final FixedBitSet bits = new FixedBitSet(maxDoc);
              for(int docID=0;docID < maxDoc;docID++) {
                // Keeps only the even ids:
                if ((acceptDocs == null || acceptDocs.get(docID)) && ((Integer.parseInt(context.reader().document(docID).get("id")) & 1) == 0)) {
                  bits.set(docID);
                }
              }
              return bits;
            }
          };
      } else {
        filter = null;
      }

      // Verify docs are always collected in order:
      new DrillSideways(s, tr).search(ddq,
                           new Collector() {
                             int lastDocID;

                             @Override
                             public void setScorer(Scorer s) {
                             }

                             @Override
                             public void collect(int doc) {
                               assert doc > lastDocID;
                               lastDocID = doc;
                             }

                             @Override
                             public void setNextReader(AtomicReaderContext context) {
                               lastDocID = -1;
                             }

                             @Override
                             public boolean acceptsDocsOutOfOrder() {
                               return false;
                             }
                           }, fsp);

      SimpleFacetResult expected = slowDrillSidewaysSearch(s, docs, contentToken, drillDowns, dimValues, filter);

      Sort sort = new Sort(new SortField("id", SortField.Type.STRING));
      DrillSidewaysResult actual = new DrillSideways(s, tr).search(ddq, filter, null, numDocs, sort, true, true, fsp);

      TopDocs hits = s.search(baseQuery, numDocs);
      Map<String,Float> scores = new HashMap<String,Float>();
      for(ScoreDoc sd : hits.scoreDocs) {
        scores.put(s.doc(sd.doc).get("id"), sd.score);
      }
      
      verifyEquals(dimValues, s, expected, actual, scores);

      // Make sure drill down doesn't change score:
      TopDocs ddqHits = s.search(ddq, filter, numDocs);
      assertEquals(expected.hits.size(), ddqHits.totalHits);
      for(int i=0;i<expected.hits.size();i++) {
        // Score should be IDENTICAL:
        assertEquals(scores.get(expected.hits.get(i).id), ddqHits.scoreDocs[i].score, 0.0f);
      }
    }

    tr.close();
    r.close();
    td.close();
    d.close();
  }

  private static class Counters {
    int[][] counts;

    public Counters(String[][] dimValues) {
      counts = new int[dimValues.length][];
      for(int dim=0;dim<dimValues.length;dim++) {
        counts[dim] = new int[dimValues[dim].length];
      }
    }

    public void inc(int[] dims, int[] dims2) {
      inc(dims, dims2, -1);
    }

    public void inc(int[] dims, int[] dims2, int onlyDim) {
      assert dims.length == counts.length;
      assert dims2.length == counts.length;
      for(int dim=0;dim<dims.length;dim++) {
        if (onlyDim == -1 || dim == onlyDim) {
          if (dims[dim] != -1) {
            counts[dim][dims[dim]]++;
          }
          if (dims2[dim] != -1 && dims2[dim] != dims[dim]) {
            counts[dim][dims2[dim]]++;
          }
        }
      }
    }
  }

  private static class SimpleFacetResult {
    List<Doc> hits;
    int[][] counts;
  }

  private SimpleFacetResult slowDrillSidewaysSearch(IndexSearcher s, List<Doc> docs, String contentToken, String[][] drillDowns,
                                                    String[][] dimValues, Filter onlyEven) throws Exception {
    int numDims = dimValues.length;

    List<Doc> hits = new ArrayList<Doc>();
    Counters drillDownCounts = new Counters(dimValues);
    Counters[] drillSidewaysCounts = new Counters[dimValues.length];
    for(int dim=0;dim<numDims;dim++) {
      drillSidewaysCounts[dim] = new Counters(dimValues);
    }

    if (VERBOSE) {
      System.out.println("  compute expected");
    }

    nextDoc: for(Doc doc : docs) {
      if (doc.deleted) {
        continue;
      }
      if (onlyEven != null & (Integer.parseInt(doc.id) & 1) != 0) {
        continue;
      }
      if (contentToken == null || doc.contentToken.equals(contentToken)) {
        int failDim = -1;
        for(int dim=0;dim<numDims;dim++) {
          if (drillDowns[dim] != null) {
            String docValue = doc.dims[dim] == -1 ? null : dimValues[dim][doc.dims[dim]];
            String docValue2 = doc.dims2[dim] == -1 ? null : dimValues[dim][doc.dims2[dim]];
            boolean matches = false;
            for(String value : drillDowns[dim]) {
              if (value.equals(docValue) || value.equals(docValue2)) {
                matches = true;
                break;
              }
            }
            if (!matches) {
              if (failDim == -1) {
                // Doc could be a near-miss, if no other dim fails
                failDim = dim;
              } else {
                // Doc isn't a hit nor a near-miss
                continue nextDoc;
              }
            }
          }
        }

        if (failDim == -1) {
          if (VERBOSE) {
            System.out.println("    exp: id=" + doc.id + " is a hit");
          }
          // Hit:
          hits.add(doc);
          drillDownCounts.inc(doc.dims, doc.dims2);
          for(int dim=0;dim<dimValues.length;dim++) {
            drillSidewaysCounts[dim].inc(doc.dims, doc.dims2);
          }
        } else {
          if (VERBOSE) {
            System.out.println("    exp: id=" + doc.id + " is a near-miss on dim=" + failDim);
          }
          drillSidewaysCounts[failDim].inc(doc.dims, doc.dims2, failDim);
        }
      }
    }

    Map<String,Integer> idToDocID = new HashMap<String,Integer>();
    for(int i=0;i<s.getIndexReader().maxDoc();i++) {
      idToDocID.put(s.doc(i).get("id"), i);
    }

    Collections.sort(hits);

    SimpleFacetResult res = new SimpleFacetResult();
    res.hits = hits;
    res.counts = new int[numDims][];
    for(int dim=0;dim<numDims;dim++) {
      if (drillDowns[dim] != null) {
        res.counts[dim] = drillSidewaysCounts[dim].counts[dim];
      } else {
        res.counts[dim] = drillDownCounts.counts[dim];
      }
    }

    return res;
  }

  void verifyEquals(String[][] dimValues, IndexSearcher s, SimpleFacetResult expected, DrillSidewaysResult actual, Map<String,Float> scores) throws Exception {
    if (VERBOSE) {
      System.out.println("  verify totHits=" + expected.hits.size());
    }
    assertEquals(expected.hits.size(), actual.hits.totalHits);
    assertEquals(expected.hits.size(), actual.hits.scoreDocs.length);
    for(int i=0;i<expected.hits.size();i++) {
      if (VERBOSE) {
        System.out.println("    hit " + i + " expected=" + expected.hits.get(i).id);
      }
      assertEquals(expected.hits.get(i).id,
                   s.doc(actual.hits.scoreDocs[i].doc).get("id"));
      // Score should be IDENTICAL:
      assertEquals(scores.get(expected.hits.get(i).id), actual.hits.scoreDocs[i].score, 0.0f);
    }
    assertEquals(expected.counts.length, actual.facetResults.size());
    for(int dim=0;dim<expected.counts.length;dim++) {
      if (VERBOSE) {
        System.out.println("    dim" + dim);
        System.out.println("      actual");
      }
      FacetResult fr = actual.facetResults.get(dim);
      Map<String,Integer> actualValues = new HashMap<String,Integer>();
      for(FacetResultNode childNode : fr.getFacetResultNode().subResults) {
        actualValues.put(childNode.label.components[1], (int) childNode.value);
        if (VERBOSE) {
          System.out.println("        " + new BytesRef(childNode.label.components[1]) + ": " + (int) childNode.value);
        }
      }

      if (VERBOSE) {
        System.out.println("      expected");
      }

      int setCount = 0;
      for(int i=0;i<dimValues[dim].length;i++) {
        String value = dimValues[dim][i];
        if (expected.counts[dim][i] != 0) {
          if (VERBOSE) {
            System.out.println("        " + new BytesRef(value) + ": " + expected.counts[dim][i]);
          } 
          assertTrue(actualValues.containsKey(value));
          assertEquals(expected.counts[dim][i], actualValues.get(value).intValue());
          setCount++;
        } else {
          assertFalse(actualValues.containsKey(value));
        }
      }

      assertEquals(setCount, actualValues.size());
    }
  }

  /** Just gathers counts of values under the dim. */
  private String toString(FacetResult fr) {
    StringBuilder b = new StringBuilder();
    FacetResultNode node = fr.getFacetResultNode();
    b.append(node.label);
    b.append(":");
    for(FacetResultNode childNode : node.subResults) {
      b.append(' ');
      b.append(childNode.label.components[1]);
      b.append('=');
      b.append((int) childNode.value);
    }
    return b.toString();
  }
}

