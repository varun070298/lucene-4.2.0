package org.apache.lucene.facet.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.FacetTestCase;
import org.apache.lucene.facet.index.FacetFields;
import org.apache.lucene.facet.params.CategoryListParams;
import org.apache.lucene.facet.params.FacetIndexingParams;
import org.apache.lucene.facet.params.FacetSearchParams;
import org.apache.lucene.facet.params.PerDimensionIndexingParams;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.facet.taxonomy.TaxonomyWriter;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.IOUtils;
import org.junit.Test;

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

public class TestFacetsCollector extends FacetTestCase {

  @Test
  public void testSumScoreAggregator() throws Exception {
    Directory indexDir = newDirectory();
    Directory taxoDir = newDirectory();

    TaxonomyWriter taxonomyWriter = new DirectoryTaxonomyWriter(taxoDir);
    IndexWriter iw = new IndexWriter(indexDir, newIndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random())));

    FacetFields facetFields = new FacetFields(taxonomyWriter);
    for(int i = atLeast(30); i > 0; --i) {
      Document doc = new Document();
      doc.add(new StringField("f", "v", Store.NO));
      facetFields.addFields(doc, Collections.singletonList(new CategoryPath("a")));
      iw.addDocument(doc);
    }
    
    taxonomyWriter.close();
    iw.close();
    
    DirectoryReader r = DirectoryReader.open(indexDir);
    DirectoryTaxonomyReader taxo = new DirectoryTaxonomyReader(taxoDir);
    
    FacetSearchParams sParams = new FacetSearchParams(new SumScoreFacetRequest(new CategoryPath("a"), 10));
    FacetsAccumulator fa = new FacetsAccumulator(sParams, r, taxo) {
      @Override
      public FacetsAggregator getAggregator() {
        return new SumScoreFacetsAggregator();
      }
    };
    FacetsCollector fc = FacetsCollector.create(fa);
    TopScoreDocCollector topDocs = TopScoreDocCollector.create(10, false);
    new IndexSearcher(r).search(new MatchAllDocsQuery(), MultiCollector.wrap(fc, topDocs));
    
    List<FacetResult> res = fc.getFacetResults();
    double value = res.get(0).getFacetResultNode().value;
    double expected = topDocs.topDocs().getMaxScore() * r.numDocs();
    assertEquals(expected, value, 1E-10);
    
    IOUtils.close(taxo, taxoDir, r, indexDir);
  }
  
  @Test
  public void testMultiCountingLists() throws Exception {
    Directory indexDir = newDirectory();
    Directory taxoDir = newDirectory();
    
    TaxonomyWriter taxonomyWriter = new DirectoryTaxonomyWriter(taxoDir);
    IndexWriter iw = new IndexWriter(indexDir, newIndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random())));
    FacetIndexingParams fip = new PerDimensionIndexingParams(Collections.singletonMap(new CategoryPath("b"), new CategoryListParams("$b")));
    
    FacetFields facetFields = new FacetFields(taxonomyWriter, fip);
    for(int i = atLeast(30); i > 0; --i) {
      Document doc = new Document();
      doc.add(new StringField("f", "v", Store.NO));
      List<CategoryPath> cats = new ArrayList<CategoryPath>();
      cats.add(new CategoryPath("a"));
      cats.add(new CategoryPath("b"));
      facetFields.addFields(doc, cats);
      iw.addDocument(doc);
    }
    
    taxonomyWriter.close();
    iw.close();
    
    DirectoryReader r = DirectoryReader.open(indexDir);
    DirectoryTaxonomyReader taxo = new DirectoryTaxonomyReader(taxoDir);
    
    FacetSearchParams sParams = new FacetSearchParams(fip,
        new CountFacetRequest(new CategoryPath("a"), 10), 
        new CountFacetRequest(new CategoryPath("b"), 10));
    FacetsCollector fc = FacetsCollector.create(sParams, r, taxo);
    new IndexSearcher(r).search(new MatchAllDocsQuery(), fc);
    
    for (FacetResult res : fc.getFacetResults()) {
      assertEquals("unexpected count for " + res, r.maxDoc(), (int) res.getFacetResultNode().value);
    }
    
    IOUtils.close(taxo, taxoDir, r, indexDir);
  }
  
  @Test
  public void testCountAndSumScore() throws Exception {
    Directory indexDir = newDirectory();
    Directory taxoDir = newDirectory();
    
    TaxonomyWriter taxonomyWriter = new DirectoryTaxonomyWriter(taxoDir);
    IndexWriter iw = new IndexWriter(indexDir, newIndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random())));
    FacetIndexingParams fip = new PerDimensionIndexingParams(Collections.singletonMap(new CategoryPath("b"), new CategoryListParams("$b")));
    
    FacetFields facetFields = new FacetFields(taxonomyWriter, fip);
    for(int i = atLeast(30); i > 0; --i) {
      Document doc = new Document();
      doc.add(new StringField("f", "v", Store.NO));
      List<CategoryPath> cats = new ArrayList<CategoryPath>();
      cats.add(new CategoryPath("a"));
      cats.add(new CategoryPath("b"));
      facetFields.addFields(doc, cats);
      iw.addDocument(doc);
    }
    
    taxonomyWriter.close();
    iw.close();
    
    DirectoryReader r = DirectoryReader.open(indexDir);
    DirectoryTaxonomyReader taxo = new DirectoryTaxonomyReader(taxoDir);
    
    FacetSearchParams sParams = new FacetSearchParams(fip,
        new CountFacetRequest(new CategoryPath("a"), 10), 
        new SumScoreFacetRequest(new CategoryPath("b"), 10));
    
    Map<CategoryListParams,FacetsAggregator> aggregators = new HashMap<CategoryListParams,FacetsAggregator>();
    aggregators.put(fip.getCategoryListParams(new CategoryPath("a")), new FastCountingFacetsAggregator());
    aggregators.put(fip.getCategoryListParams(new CategoryPath("b")), new SumScoreFacetsAggregator());
    final FacetsAggregator aggregator = new PerCategoryListAggregator(aggregators, fip);
    FacetsAccumulator fa = new FacetsAccumulator(sParams, r, taxo) {
      @Override
      public FacetsAggregator getAggregator() {
        return aggregator;
      }
    };
    
    FacetsCollector fc = FacetsCollector.create(fa);
    TopScoreDocCollector topDocs = TopScoreDocCollector.create(10, false);
    new IndexSearcher(r).search(new MatchAllDocsQuery(), MultiCollector.wrap(fc, topDocs));
    
    List<FacetResult> facetResults = fc.getFacetResults();
    FacetResult fresA = facetResults.get(0);
    assertEquals("unexpected count for " + fresA, r.maxDoc(), (int) fresA.getFacetResultNode().value);
    
    FacetResult fresB = facetResults.get(1);
    double expected = topDocs.topDocs().getMaxScore() * r.numDocs();
    assertEquals("unexpected value for " + fresB, expected, fresB.getFacetResultNode().value, 1E-10);
    
    IOUtils.close(taxo, taxoDir, r, indexDir);
  }

}
