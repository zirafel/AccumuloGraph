/* Copyright 2014 The Johns Hopkins University Applied Physics Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.jhuapl.tinkerpop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.regex.Pattern;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.util.PeekingIterator;
import org.apache.commons.configuration.Configuration;
import org.apache.hadoop.io.Text;

import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Features;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Index;
import com.tinkerpop.blueprints.IndexableGraph;
import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.util.DefaultGraphQuery;
import com.tinkerpop.blueprints.util.ExceptionFactory;
import com.tinkerpop.blueprints.util.StringFactory;

import edu.jhuapl.tinkerpop.cache.ElementCaches;
import edu.jhuapl.tinkerpop.mutator.Mutators;
import edu.jhuapl.tinkerpop.tables.EdgeTableWrapper;
import edu.jhuapl.tinkerpop.tables.VertexTableWrapper;

/**
 * 
 * This is an implementation of TinkerPop's graph API
 * backed by Apache Accumulo. In addition to the basic
 * Graph interface, the implementation
 * supports {@link IndexableGraph} and {@link KeyIndexableGraph}.
 * 
 * <p/>Tables have the following formats.
 * 
 * <p/>
 * <table border="1">
 *  <caption>Vertex table</caption>
 *  <thead>
 *    <tr><th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>VertexID</td><td>LABEL</td><td>EXISTS</td><td>[empty]</td></tr>
 *    <tr><td>VertexID</td><td>INEDGE</td><td>InVertexID_EdgeID</td><td>EdgeLabel</td></tr>
 *    <tr><td>VertexID</td><td>OUTEDGE</td><td>OutVertexID_EdgeID</td><td>EdgeLabel</td></tr>
 *    <tr><td>VertexID</td><td>PropertyKey</td><td>[empty]</td><td>Encoded Value</td></tr>
 *  </tbody>
 * </table>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Edge table</caption>
 *  <thead>
 *    <tr> <th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>EdgeID</td><td>LABEL</td><td>[empty]</td><td>Encoded LabelValue</td></tr>
 *    <tr><td>EdgeID</td><td>INEDGE</td><td>InVertexID</td><td>[empty]</td></tr>
 *    <tr><td>EdgeID</td><td>OUTEDGE</td><td>OutVertexID</td><td>[empty]</td></tr>
 *    <tr><td>EdgeID</td><td>PropertyKey</td><td>[empty]</td><td>Encoded Value</td></tr>
 *  </tbody>
 * </table>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Vertex / edge index tables (each index gets its own table)</caption>
 *  <thead>
 *    <tr><th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>Encoded Value</td><td>PropertyKey</td><td>ElementID</td><td>[empty]</td></tr>
 *  </tbody>
 * </table>
 * 
 * <p/>
 * <table border="1">
 *  <caption>Metadata/key metadata tables</caption>
 *  <thead>
 *    <tr><th>ROWID</th><th>COLFAM</th><th>COLQUAL</th><th>VALUE</th></tr>
 *  </thead>
 *  <tbody>
 *    <tr><td>IndexName</td><td>IndexClassType</td><td>[empty]</td><td>[empty]</td></tr>
 *  </tbody>
 * </table>
 */
public class AccumuloGraph implements Graph, KeyIndexableGraph, IndexableGraph {

  protected GlobalInstances globals;

  protected AccumuloGraphConfiguration config;

  public static final byte[] EMPTY = new byte[0];

  public static final String IDDELIM = "_";
  public static final String SLABEL = "L";
  public static final String SINEDGE = "I";
  public static final String SOUTEDGE = "O";
  public static final String SEXISTS = "E";
  public static final byte[] EXISTS = SEXISTS.getBytes();
  public static final byte[] LABEL = SLABEL.getBytes();
  public static final byte[] INEDGE = SINEDGE.getBytes();
  public static final byte[] OUTEDGE = SOUTEDGE.getBytes();
  public static final Text TEXISTS = new Text(EXISTS);
  public static final Text TINEDGE = new Text(INEDGE);
  public static final Text TOUTEDGE = new Text(OUTEDGE);
  public static final Text TLABEL = new Text(LABEL);

  MultiTableBatchWriter writer;
  BatchWriter vertexBW;
  BatchWriter edgeBW;

  private ElementCaches caches;

  VertexTableWrapper vertexWrapper;
  EdgeTableWrapper edgeWrapper;

  public AccumuloGraph(Configuration cfg) {
    this(new AccumuloGraphConfiguration(cfg));
  }

  /**
   * Constructor that ensures that the needed tables are made
   * 
   * @param config
   */
  public AccumuloGraph(AccumuloGraphConfiguration config) {
    config.validate();
    this.config = config;

    this.caches = new ElementCaches(config);

    AccumuloGraphUtils.handleCreateAndClear(config);

    try {
      setupWriters();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }

    globals = new GlobalInstances(this, config, writer, caches);

    vertexWrapper = new VertexTableWrapper(globals);
    edgeWrapper = new EdgeTableWrapper(globals);

    globals.setVertexWrapper(vertexWrapper);
    globals.setEdgeWrapper(edgeWrapper);
  }

  private void setupWriters() throws Exception {
    writer = config.getConnector().createMultiTableBatchWriter(config.getBatchWriterConfig());

    vertexBW = writer.getBatchWriter(config.getVertexTableName());
    edgeBW = writer.getBatchWriter(config.getEdgeTableName());
  }

  /**
   * Factory method for GraphFactory
   */
  public static AccumuloGraph open(Configuration properties) throws AccumuloException {
    return new AccumuloGraph(properties);
  }

  protected Scanner getElementScanner(Class<? extends Element> type) {
    try {
      String tableName = config.getEdgeTableName();
      if (type.equals(Vertex.class))
        tableName = config.getVertexTableName();
      return config.getConnector().createScanner(tableName, config.getAuthorizations());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  protected Scanner getScanner(String tablename) {
    try {
      return config.getConnector().createScanner(tablename, config.getAuthorizations());
    } catch (TableNotFoundException e) {
      e.printStackTrace();
    } catch (AccumuloException e) {
      e.printStackTrace();
    } catch (AccumuloSecurityException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    return null;
  }

  // Aliases for the lazy
  protected Scanner getMetadataScanner() {
    return getScanner(config.getMetadataTableName());
  }

  protected Scanner getVertexIndexScanner() {
    return getScanner(config.getVertexKeyIndexTableName());
  }

  protected Scanner getEdgeIndexScanner() {
    return getScanner(config.getEdgeKeyIndexTableName());
  }

  protected BatchWriter getVertexIndexWriter() {
    return getWriter(config.getVertexKeyIndexTableName());
  }

  protected BatchWriter getMetadataWriter() {
    return getWriter(config.getMetadataTableName());
  }

  protected BatchWriter getEdgeIndexWriter() {
    return getWriter(config.getEdgeKeyIndexTableName());
  }

  private Scanner getKeyMetadataScanner() {
    return getScanner(config.getKeyMetadataTableName());
  }

  protected BatchWriter getKeyMetadataWriter() {
    return getWriter(config.getKeyMetadataTableName());
  }

  public BatchWriter getWriter(String tablename) {
    try {
      return writer.getBatchWriter(tablename);
    } catch (AccumuloException e) {
      e.printStackTrace();
    } catch (AccumuloSecurityException e) {
      e.printStackTrace();
    } catch (TableNotFoundException e) {
      e.printStackTrace();
    }
    return null;
  }

  private BatchScanner getElementBatchScanner(Class<? extends Element> type) {
    try {
      String tableName = config.getVertexTableName();
      if (type.equals(Edge.class))
        tableName = config.getEdgeTableName();
      BatchScanner x = config.getConnector().createBatchScanner(tableName, config.getAuthorizations(), config.getQueryThreads());
      x.setRanges(Collections.singletonList(new Range()));
      return x;
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
  }

  // End Aliases

  @Override
  public Features getFeatures() {
    return AccumuloFeatures.get();
  }

  @Override
  public Vertex addVertex(Object id) {
    if (id == null) {
      id = AccumuloGraphUtils.generateId();
    }

    String myID = id.toString();

    Vertex vert = null;
    if (!config.getSkipExistenceChecks()) {
      vert = getVertex(myID);
      if (vert != null) {
        throw ExceptionFactory.vertexWithIdAlreadyExists(myID);
      }
    }

    vert = new AccumuloVertex(globals, myID);

    vertexWrapper.writeVertex(vert);
    checkedFlush();

    caches.cache(vert, Vertex.class);

    return vert;
  }

  @Override
  public Vertex getVertex(Object id) {
    if (id == null) {
      throw ExceptionFactory.vertexIdCanNotBeNull();
    }
    String myID = id.toString();

    Vertex vertex = caches.retrieve(myID, Vertex.class);
    if (vertex != null) {
      return vertex;
    }

    vertex = new AccumuloVertex(globals, myID);
    if (!config.getSkipExistenceChecks()) {
      // In addition to just an "existence" check, we will also load
      // any "preloaded" properties now, which saves us a round-trip
      // to Accumulo later.
      String[] preload = config.getPreloadedProperties();
      if (preload == null) {
        preload = new String[]{};
      }

      Map<String, Object> props = vertexWrapper.readProperties(vertex, preload);
      if (props == null) {
        return null;
      }
      ((AccumuloElement) vertex).cacheAllProperties(props);
    }

    caches.cache(vertex, Vertex.class);

    return vertex;
  }

  @Override
  public void removeVertex(Vertex vertex) {
    caches.remove(vertex.getId(), Vertex.class);

    if (!config.getIndexableGraphDisabled())
      clearIndex(vertex.getId());

    Scanner scan = getElementScanner(Vertex.class);
    scan.setRange(new Range(vertex.getId().toString()));

    BatchDeleter edgedeleter = null;
    BatchDeleter vertexdeleter = null;
    BatchWriter indexdeleter = getVertexIndexWriter();
    try {
      // Set up Deleters
      edgedeleter = config.getConnector().createBatchDeleter(config.getEdgeTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
      vertexdeleter = config.getConnector().createBatchDeleter(config.getVertexTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }
    Iterator<Entry<Key,Value>> iter = scan.iterator();
    List<Range> ranges = new ArrayList<Range>();
    if (!iter.hasNext()) {
      throw ExceptionFactory.vertexWithIdDoesNotExist(vertex.getId());
    }
    try {
      // Search for edges
      while (iter.hasNext()) {
        Entry<Key,Value> e = iter.next();
        Key k = e.getKey();

        if (k.getColumnFamily().equals(TOUTEDGE) || k.getColumnFamily().equals(TINEDGE)) {
          ranges.add(new Range(k.getColumnQualifier().toString().split(IDDELIM)[1]));

          Mutation vm = new Mutation(k.getColumnQualifier().toString().split(IDDELIM)[0]);
          vm.putDelete(invert(k.getColumnFamily()), new Text(vertex.getId().toString() + IDDELIM + k.getColumnQualifier().toString().split(IDDELIM)[1]));
          vertexBW.addMutation(vm);
        } else {
          Mutation m = new Mutation(e.getValue().get());
          m.putDelete(k.getColumnFamily(), k.getRow());
          indexdeleter.addMutation(m);
        }

      }
      checkedFlush();
      scan.close();

      // If Edges are found, delete the whole row
      if (!ranges.isEmpty()) {
        // TODO don't we also have to propagate these deletes to the
        // vertex index table?
        edgedeleter.setRanges(ranges);
        edgedeleter.delete();
        ranges.clear();
      }
      // Delete the whole vertex row
      ranges.add(new Range(vertex.getId().toString()));
      vertexdeleter.setRanges(ranges);
      vertexdeleter.delete();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (edgedeleter != null)
        edgedeleter.close();
      if (vertexdeleter != null)
        vertexdeleter.close();
    }
  }

  // Maybe an Custom Iterator could make this better.
  private void clearIndex(Object id) {
    Iterable<Index<? extends Element>> it = this.getIndices();
    Iterator<Index<? extends Element>> iter = it.iterator();
    while (iter.hasNext()) {
      AccumuloIndex<?> in = (AccumuloIndex<?>) iter.next();
      String table = in.tableName;

      BatchDeleter del = null;
      try {
        del = config.getConnector().createBatchDeleter(table, config.getAuthorizations(), config.getMaxWriteThreads(), config.getBatchWriterConfig());
        del.setRanges(Collections.singleton(new Range()));
        StringBuilder regex = new StringBuilder();
        regex.append(".*\\Q").append(id.toString()).append("\\E$");

        IteratorSetting is = new IteratorSetting(10, "getEdgeFilter", RegExFilter.class);
        RegExFilter.setRegexs(is, null, null, regex.toString(), null, false);
        del.addScanIterator(is);
        del.delete();
      } catch (Exception e) {
        throw new AccumuloGraphException(e);
      } finally {
        if (del != null)
          del.close();
      }
    }
  }

  private Text invert(Text columnFamily) {
    if (columnFamily.toString().equals(INEDGE)) {
      return TOUTEDGE;
    }
    return TINEDGE;
  }

  @Override
  public Iterable<Vertex> getVertices() {
    return globals.getVertexWrapper().getVertices();
  }

  @Override
  public Iterable<Vertex> getVertices(String key, Object value) {
    checkProperty(key, value);
    if (config.getAutoIndex() || getIndexedKeys(Vertex.class).contains(key)) {
      // Use the index
      Scanner s = getVertexIndexScanner();
      byte[] val = AccumuloByteSerializer.serialize(value);
      Text tVal = new Text(val);
      s.setRange(new Range(tVal, tVal));
      s.fetchColumnFamily(new Text(key));

      return new ScannerIterable<Vertex>(s) {

        @Override
        public Vertex next(PeekingIterator<Entry<Key,Value>> iterator) {

          Key key = iterator.next().getKey();
          Vertex v = caches.retrieve(key.getColumnQualifier().toString(), Vertex.class);

          v = (v == null ? new AccumuloVertex(globals, key.getColumnQualifier().toString()) : v);
          ((AccumuloElement) v).cacheProperty(key.getColumnFamily().toString(),
              AccumuloByteSerializer.deserialize(key.getRow().getBytes()));

          caches.cache(v, Vertex.class);

          return v;

        }
      };
    } else {
      byte[] val = AccumuloByteSerializer.serialize(value);
      if (val[0] != AccumuloByteSerializer.SERIALIZABLE) {
        BatchScanner scan = getElementBatchScanner(Vertex.class);
        scan.fetchColumnFamily(new Text(key));

        IteratorSetting is = new IteratorSetting(10, "filter", RegExFilter.class);
        RegExFilter.setRegexs(is, null, null, null, Pattern.quote(new String(val)), false);
        scan.addScanIterator(is);

        return new ScannerIterable<Vertex>(scan) {

          @Override
          public Vertex next(PeekingIterator<Entry<Key,Value>> iterator) {

            Entry<Key,Value> kv = iterator.next();

            Vertex v = caches.retrieve(kv.getKey().getRow().toString(), Vertex.class);

            v = (v == null ? new AccumuloVertex(globals, kv.getKey().getRow().toString()) : v);
            ((AccumuloElement) v).cacheProperty(kv.getKey().getColumnFamily().toString(),
                AccumuloByteSerializer.deserialize(kv.getValue().get()));

            caches.cache(v, Vertex.class);

            return v;
          }
        };
      } else {
        // TODO
        throw new UnsupportedOperationException("Filtering on binary data not currently supported.");
      }
    }
  }

  @Override
  public Edge addEdge(Object id, Vertex outVertex, Vertex inVertex, String label) {
    if (label == null) {
      throw ExceptionFactory.edgeLabelCanNotBeNull();
    }
    if (id == null) {
      id = AccumuloGraphUtils.generateId();
    }

    String myID = id.toString();

    AccumuloEdge edge = new AccumuloEdge(globals, myID, inVertex, outVertex, label);

    // TODO we arent suppose to make sure the given edge ID doesn't already
    // exist?

    edgeWrapper.writeEdge(edge);
    vertexWrapper.writeEdgeEndpoints(edge);

    checkedFlush();

    caches.cache(edge, Edge.class);

    return edge;
  }

  @Override
  public Edge getEdge(Object id) {
    if (id == null) {
      throw ExceptionFactory.edgeIdCanNotBeNull();
    }
    String myID = id.toString();

    Edge edge = caches.retrieve(myID, Edge.class);
    if (edge != null) {
      return edge;
    }

    edge = new AccumuloEdge(globals, myID);

    if (!config.getSkipExistenceChecks()) {
      // In addition to just an "existence" check, we will also load
      // any "preloaded" properties now, which saves us a round-trip
      // to Accumulo later.
      String[] preload = config.getPreloadedProperties();
      if (preload == null) {
        preload = new String[]{};
      }

      Map<String, Object> props = edgeWrapper.readProperties(edge, preload);
      if (props == null) {
        return null;
      }
      ((AccumuloElement) edge).cacheAllProperties(props);
    }

    caches.cache(edge, Edge.class);

    return edge;
  }

  @Override
  public void removeEdge(Edge edge) {
    if (!config.getIndexableGraphDisabled())
      clearIndex(edge.getId());

    caches.remove(edge.getId(), Edge.class);

    Scanner s = getElementScanner(Edge.class);
    s.setRange(new Range(edge.getId().toString()));

    Iterator<Entry<Key,Value>> iter = s.iterator();
    Text inVert = null;
    Text outVert = null;
    List<Mutation> indexMutations = new ArrayList<Mutation>();
    while (iter.hasNext()) {
      Entry<Key,Value> e = iter.next();
      Key k = e.getKey();
      if (k.getColumnFamily().equals(TLABEL)) {
        String[] ids = k.getColumnQualifier().toString().split(IDDELIM);
        inVert = new Text(ids[0]);
        outVert = new Text(ids[1]);
      } else {
        Mutation m = new Mutation(k.getColumnQualifier());
        m.putDelete(k.getColumnFamily(), k.getRow());
        indexMutations.add(m);
      }
    }
    s.close();
    if (inVert == null || outVert == null) {
      return;
    }

    BatchDeleter edgedeleter = null;
    try {
      getEdgeIndexWriter().addMutations(indexMutations);
      globals.getVertexWrapper().deleteEdgeEndpoints(edge);
      globals.getEdgeWrapper().deleteEdge(edge);

      checkedFlush();
      edgedeleter = config.getConnector().createBatchDeleter(config.getVertexTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
      Mutators.deleteElementRanges(edgedeleter, edge);
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (edgedeleter != null)
        edgedeleter.close();
    }
  }

  @Override
  public Iterable<Edge> getEdges() {
    return globals.getEdgeWrapper().getEdges();
  }

  @Override
  public Iterable<Edge> getEdges(String key, Object value) {
    nullCheckProperty(key, value);
    if (key.equalsIgnoreCase("label")) {
      key = SLABEL;
    }

    if (config.getAutoIndex() || getIndexedKeys(Edge.class).contains(key)) {
      // Use the index
      Scanner s = getEdgeIndexScanner();
      byte[] val = AccumuloByteSerializer.serialize(value);
      Text tVal = new Text(val);
      s.setRange(new Range(tVal, tVal));
      s.fetchColumnFamily(new Text(key));

      return new ScannerIterable<Edge>(s) {
        @Override
        public Edge next(PeekingIterator<Entry<Key,Value>> iterator) {
          Entry<Key,Value> kv = iterator.next();
          
          Edge e = caches.retrieve(kv.getKey().getColumnQualifier().toString(), Edge.class);
          e = (e == null ? new AccumuloEdge(globals, kv.getKey().getColumnQualifier().toString()) : e);

          ((AccumuloElement) e).cacheProperty(kv.getKey().getColumnFamily().toString(),
              AccumuloByteSerializer.deserialize(kv.getKey().getRow().getBytes()));
          caches.cache(e, Edge.class);
          return e;
        }
      };
    } else {

      BatchScanner scan = getElementBatchScanner(Edge.class);
      scan.fetchColumnFamily(new Text(key));

      byte[] val = AccumuloByteSerializer.serialize(value);
      if (val[0] != AccumuloByteSerializer.SERIALIZABLE) {
        IteratorSetting is = new IteratorSetting(10, "filter", RegExFilter.class);
        RegExFilter.setRegexs(is, null, null, null, Pattern.quote(new String(val)), false);
        scan.addScanIterator(is);

        return new ScannerIterable<Edge>(scan) {

          @Override
          public Edge next(PeekingIterator<Entry<Key,Value>> iterator) {

            Key k = iterator.next().getKey();

            if (k.getColumnFamily().compareTo(AccumuloGraph.TLABEL) == 0) {
              String[] vals = k.getColumnQualifier().toString().split(AccumuloGraph.IDDELIM);
              return new AccumuloEdge(globals, k.getRow().toString(),
                  new AccumuloVertex(globals, vals[0]),
                  new AccumuloVertex(globals, vals[1]), null);
            }
            return new AccumuloEdge(globals, k.getRow().toString());
          }
        };
      } else {
        // TODO
        throw new UnsupportedOperationException("Filtering on binary data not currently supported.");
      }
    }
  }

  // TODO Eventually
  @Override
  public GraphQuery query() {
    return new DefaultGraphQuery(this);
  }

  @Override
  public void shutdown() {
    try {
      writer.close();
      vertexWrapper.close();
      edgeWrapper.close();
    } catch (MutationsRejectedException e) {
      e.printStackTrace();
    }
    caches.clear(Vertex.class);
    caches.clear(Edge.class);
  }

  // public methods not defined by Graph interface, but potentially useful for
  // applications that know they are using an AccumuloGraph
  public void clear() {
    shutdown();

    try {
      TableOperations to;
      to = config.getConnector().tableOperations();
      Iterable<Index<? extends Element>> it = this.getIndices();
      Iterator<Index<? extends Element>> iter = it.iterator();
      while (iter.hasNext()) {
        AccumuloIndex<?> in = (AccumuloIndex<?>) iter.next();
        to.delete(in.tableName);
      }

      for (String t : config.getTableNames()) {
        if (to.exists(t)) {
          to.delete(t);
          to.create(t);
          SortedSet<Text> splits = config.getSplits();
          if (splits != null) {
            to.addSplits(t, splits);
          }
        }
      }
      setupWriters();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    }

  }

  public void flush() {
    try {
      writer.flush();
    } catch (MutationsRejectedException e) {
      throw new AccumuloGraphException(e);
    }
  }

  /**
   * @deprecated Move this somewhere appropriate
   */
  void checkedFlush() {
    if (config.getAutoFlush()) {
      flush();
    }
  }

  // methods used by AccumuloElement, AccumuloVertex, AccumuloEdge to interact
  // with the backing Accumulo data store...

  /**
   * Sets the property. Requires a round-trip to Accumulo to see if the property exists
   * iff the provided key has an index. Therefore, for best performance, if at
   * all possible, create indices after bulk ingest.
   * 
   * @deprecated Move to appropriate place
   * @param type
   * @param id
   * @param key
   * @param val
   */
  void setPropertyForIndexes(Class<? extends Element> type, Element element, String key, Object val) {
    checkProperty(key, val);
    try {
      if (config.getAutoIndex() || getIndexedKeys(type).contains(key)) {
        byte[] newByteVal = AccumuloByteSerializer.serialize(val);

        BatchWriter bw = getIndexBatchWriter(type);
        Object old = element.getProperty(key);
        if (old != null) {
          byte[] oldByteVal = AccumuloByteSerializer.serialize(old);
          Mutation m = new Mutation(oldByteVal);
          m.putDelete(key, element.getId().toString());
          bw.addMutation(m);
        }
        Mutation m = new Mutation(newByteVal);
        m.put(key.getBytes(), element.getId().toString().getBytes(), EMPTY);
        bw.addMutation(m);
        checkedFlush();
      }
    } catch (MutationsRejectedException e) {
      e.printStackTrace();
    }
  }

  private BatchWriter getIndexBatchWriter(Class<? extends Element> type) {
    if (type.equals(Edge.class))
      return getEdgeIndexWriter();
    return getVertexIndexWriter();
  }

  /**
   * @deprecated Move to appropriate place
   * @param type
   * @param element
   * @param key
   * @return
   */
  void removePropertyFromIndex(Class<? extends Element> type, Element element,
      String key, Object obj) {
    try {
      if (obj != null) {
        byte[] val = AccumuloByteSerializer.serialize(obj);
        Mutation m = new Mutation(val);
        m.putDelete(key, element.getId().toString());
        getIndexBatchWriter(type).addMutation(m);
        checkedFlush();
      }
    } catch (MutationsRejectedException e) {
      e.printStackTrace();
    }
  }

  private void nullCheckProperty(String key, Object val) {
    if (key == null) {
      throw ExceptionFactory.propertyKeyCanNotBeNull();
    } else if (val == null) {
      throw ExceptionFactory.propertyValueCanNotBeNull();
    } else if (key.trim().equals(StringFactory.EMPTY_STRING)) {
      throw ExceptionFactory.propertyKeyCanNotBeEmpty();
    }
  }

  // internal methods used by this class

  private void checkProperty(String key, Object val) {
    nullCheckProperty(key, val);
    if (key.equals(StringFactory.ID)) {
      throw ExceptionFactory.propertyKeyIdIsReserved();
    } else if (key.equals(StringFactory.LABEL)) {
      throw ExceptionFactory.propertyKeyLabelIsReservedForEdges();
    } else if (val == null) {
      throw ExceptionFactory.propertyValueCanNotBeNull();
    }
  }

  @Override
  public String toString() {
    return AccumuloGraphConfiguration.ACCUMULO_GRAPH_CLASS.getSimpleName().toLowerCase();
  }

  @Override
  public <T extends Element> Index<T> createIndex(String indexName,
      Class<T> indexClass, Parameter... indexParameters) {
    if (indexClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    Scanner s = this.getMetadataScanner();
    try {
      s.setRange(new Range(indexName, indexName));
      if (s.iterator().hasNext())
        throw ExceptionFactory.indexAlreadyExists(indexName);

      BatchWriter writer = getWriter(config.getMetadataTableName());
      Mutation m = new Mutation(indexName);
      m.put(indexClass.getSimpleName().getBytes(), EMPTY, EMPTY);
      try {
        writer.addMutation(m);
      } catch (MutationsRejectedException e) {
        e.printStackTrace();
      }
      return new AccumuloIndex<T>(indexClass, globals, indexName);
    } finally {
      s.close();
    }
  }

  @Override
  public <T extends Element> Index<T> getIndex(String indexName, Class<T> indexClass) {
    if (indexClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");

    Scanner scan = getScanner(config.getMetadataTableName());
    try {
      scan.setRange(new Range(indexName, indexName));
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      while (iter.hasNext()) {
        Key k = iter.next().getKey();
        if (k.getColumnFamily().toString().equals(indexClass.getSimpleName())) {
          return new AccumuloIndex<T>(indexClass, globals, indexName);
        } else {
          throw ExceptionFactory.indexDoesNotSupportClass(indexName, indexClass);
        }
      }
      return null;
    } finally {
      scan.close();
    }
  }

  @Override
  public Iterable<Index<? extends Element>> getIndices() {
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");
    List<Index<? extends Element>> toRet = new ArrayList<Index<? extends Element>>();
    Scanner scan = getScanner(config.getMetadataTableName());
    try {
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      while (iter.hasNext()) {
        Key k = iter.next().getKey();
        toRet.add(new AccumuloIndex(getClass(k.getColumnFamily().toString()),
            globals, k.getRow().toString()));
      }
      return toRet;
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      scan.close();
    }
  }

  private Class<? extends Element> getClass(String e) {
    if (e.equals("Vertex")) {
      return Vertex.class;
    }
    return Edge.class;
  }

  @Override
  public void dropIndex(String indexName) {
    if (config.getIndexableGraphDisabled())
      throw new UnsupportedOperationException("IndexableGraph is disabled via the configuration");
    BatchDeleter deleter = null;
    try {

      deleter = config.getConnector().createBatchDeleter(config.getMetadataTableName(), config.getAuthorizations(), config.getQueryThreads(),
          config.getBatchWriterConfig());
      deleter.setRanges(Collections.singleton(new Range(indexName)));
      deleter.delete();
      config.getConnector().tableOperations().delete(config.getGraphName() + "_index_" + indexName);
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (deleter != null)
        deleter.close();
    }
  }

  @Override
  public <T extends Element> void dropKeyIndex(String key, Class<T> elementClass) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }

    String table = null;
    if (elementClass.equals(Vertex.class)) {
      table = config.getVertexKeyIndexTableName();
    } else {
      table = config.getEdgeKeyIndexTableName();
    }
    BatchWriter w = getKeyMetadataWriter();
    BatchDeleter bd = null;
    Mutation m = new Mutation(key);
    m.putDelete(elementClass.getSimpleName().getBytes(), EMPTY);
    try {
      bd = config.getConnector().createBatchDeleter(table, config.getAuthorizations(), config.getMaxWriteThreads(), config.getBatchWriterConfig());
      w.addMutation(m);
      bd.setRanges(Collections.singleton(new Range()));
      bd.fetchColumnFamily(new Text(key));
      bd.delete();
    } catch (Exception e) {
      throw new AccumuloGraphException(e);
    } finally {
      if (bd != null)
        bd.close();
    }
    checkedFlush();
  }

  @Override
  public <T extends Element> void createKeyIndex(String key,
      Class<T> elementClass, Parameter... indexParameters) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }
    BatchWriter w = getKeyMetadataWriter();

    Mutation m = new Mutation(key);
    m.put(elementClass.getSimpleName().getBytes(), EMPTY, EMPTY);
    try {
      w.addMutation(m);
    } catch (MutationsRejectedException e) {
      e.printStackTrace();
    }
    checkedFlush();
    // Re Index Graph
    BatchScanner scan = getElementBatchScanner(elementClass);
    try {
      scan.setRanges(Collections.singleton(new Range()));
      scan.fetchColumnFamily(new Text(key));
      Iterator<Entry<Key,Value>> iter = scan.iterator();

      BatchWriter bw = getIndexBatchWriter(elementClass);
      while (iter.hasNext()) {
        Entry<Key,Value> entry = iter.next();
        Key k = entry.getKey();
        Value v = entry.getValue();
        Mutation mu = new Mutation(v.get());
        mu.put(k.getColumnFamily().getBytes(), k.getRow().getBytes(), EMPTY);
        try {
          bw.addMutation(mu);
        } catch (MutationsRejectedException e) {
          // TODO handle this better.
          throw new AccumuloGraphException(e);
        }
      }
    } finally {
      scan.close();
    }
    checkedFlush();

  }

  @Override
  public <T extends Element> Set<String> getIndexedKeys(Class<T> elementClass) {
    if (elementClass == null) {
      throw ExceptionFactory.classForElementCannotBeNull();
    }

    Scanner s = getKeyMetadataScanner();

    try {
      s.fetchColumnFamily(new Text(elementClass.getSimpleName()));
      Iterator<Entry<Key,Value>> iter = s.iterator();
      Set<String> toRet = new HashSet<String>();
      while (iter.hasNext()) {
        toRet.add(iter.next().getKey().getRow().toString());
      }
      return toRet;
    } finally {
      s.close();
    }
  }

  public boolean isEmpty() {
    for (String t : config.getTableNames()) {
      if (getScanner(t).iterator().hasNext()) {
        return false;
      }
    }

    return true;
  }
}
