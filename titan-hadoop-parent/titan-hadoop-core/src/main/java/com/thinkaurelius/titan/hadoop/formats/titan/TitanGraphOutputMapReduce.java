package com.thinkaurelius.titan.hadoop.formats.titan;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.thinkaurelius.titan.core.*;
import com.thinkaurelius.titan.core.attribute.Cmp;
import com.thinkaurelius.titan.graphdb.types.system.BaseVertexLabel;
import com.thinkaurelius.titan.graphdb.types.system.ImplicitKey;
import com.thinkaurelius.titan.hadoop.*;
import com.thinkaurelius.titan.hadoop.FaunusProperty;
import com.thinkaurelius.titan.hadoop.compat.HadoopCompatLoader;
import com.thinkaurelius.titan.hadoop.config.ConfigurationUtil;
import com.thinkaurelius.titan.hadoop.formats.titan.cassandra.TitanCassandraOutputFormat;
import com.thinkaurelius.titan.hadoop.mapreduce.util.EmptyConfiguration;
import com.tinkerpop.blueprints.*;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.tinkerpop.blueprints.Direction.IN;
import static com.tinkerpop.blueprints.Direction.OUT;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public class TitanGraphOutputMapReduce {

    public enum Counters {
        VERTICES_ADDED,
        VERTICES_REMOVED,
        VERTEX_PROPERTIES_ADDED,
        VERTEX_PROPERTIES_REMOVED,
        EDGES_ADDED,
        EDGES_REMOVED,
        EDGE_PROPERTIES_ADDED,
        EDGE_PROPERTIES_REMOVED,
        NULL_VERTEX_EDGES_IGNORED,
        NULL_VERTICES_IGNORED,
        NULL_RELATIONS_IGNORED,
        SUCCESSFUL_TRANSACTIONS,
        FAILED_TRANSACTIONS
    }

    public static final Logger LOGGER = Logger.getLogger(TitanGraphOutputMapReduce.class);
    // some random property that will 'never' be used by anyone
    public static final String TITAN_ID = "_bId0192834";
    public static final String ID_MAP_KEY = "_iDMaPKeY";

    private static final String HADOOP_VERTEX = "hadoopVertex";
    private static final String HADOOP_EDGE = "hadoopEdge";
    private static final String TITAN_OUT_VERTEX = "titanOutVertex";
    private static final String TITAN_IN_VERTEX = "titanInVertex";
    private static final String GRAPH = "graph";
    private static final String MAP_CONTEXT = "mapContext";

    /*private static final String FAUNUS_VERTEX = "faunusVertex";
    private static final String GRAPH = "graph";
    private static final String MAP_CONTEXT = "mapContext"; */

    public static TitanGraph generateGraph(final Configuration configuration) {
        final Class<? extends OutputFormat> format = configuration.getClass(HadoopGraph.TITAN_HADOOP_GRAPH_OUTPUT_FORMAT, OutputFormat.class, OutputFormat.class);
        if (TitanOutputFormat.class.isAssignableFrom(format)) {
            return TitanFactory.open(ConfigurationUtil.extractOutputConfiguration(configuration));
        } else {
            throw new RuntimeException("The provide graph output format is not a supported TitanOutputFormat: " + format.getName());
        }
    }

    public static Configuration createConfiguration() {
        final Configuration configuration = new EmptyConfiguration();
        configuration.setBoolean("mapred.map.tasks.speculative.execution", false);
        configuration.setBoolean("mapred.reduce.tasks.speculative.execution", false);
        configuration.set("titan.hadoop.input.storage.backend", "embeddedcassandra");
        configuration.set("titan.hadoop.output.storage.backend", "embeddedcassandra");
        configuration.set("titan.hadoop.output.storage.conf-file", TitanCassandraOutputFormat.class.getResource("cassandra.yaml").toString());
        configuration.set("titan.hadoop.output.cache.db-cache", "false");
        return configuration;
    }

    //UTILITY METHODS
    private static Object getValue(TitanRelation relation, TitanGraph graph) {
        if (relation.isProperty()) return ((TitanProperty)relation).getValue();
        else return graph.getVertex(((TitanEdge) relation).getVertex(IN).getLongId());
    }

    // WRITE ALL THE VERTICES AND THEIR PROPERTIES
    public static class VertexMap extends Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>> {

        public TitanGraph graph;
        boolean trackState;

        private final Holder<FaunusVertex> vertexHolder = new Holder<FaunusVertex>();
        private final LongWritable longWritable = new LongWritable();

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.graph = TitanGraphOutputMapReduce.generateGraph(context.getConfiguration());
            this.trackState = context.getConfiguration().getBoolean(Tokens.TITAN_HADOOP_PIPELINE_TRACK_STATE, false);
            LOGGER.setLevel(Level.INFO);
        }

        @Override
        public void map(final NullWritable key, final FaunusVertex value, final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws IOException, InterruptedException {
            try {
                final TitanVertex titanVertex = this.getCreateOrDeleteVertex(value, context);
                if (null != titanVertex) { // the vertex was state != deleted (if it was we know incident edges are deleted too)
                    // Propagate shell vertices with Titan ids
                    final FaunusVertex shellVertex = new FaunusVertex(context.getConfiguration(), value.getLongId());
                    shellVertex.setProperty(TITAN_ID, titanVertex.getLongId());
                    for (final TitanEdge edge : value.query().direction(OUT).titanEdges()) {
                        if (!trackState || edge.isNew()) { //Only need to propagate ids for new edges
                            this.longWritable.set(edge.getVertex(IN).getLongId());
                            context.write(this.longWritable, this.vertexHolder.set('s', shellVertex));
                        }
                    }

                    this.longWritable.set(value.getLongId());
//                    value.getPropertiesWithState().clear();  // no longer needed in reduce phase
                    value.setProperty(TITAN_ID, titanVertex.getLongId()); // need this for id resolution in edge-map phase
//                    value.removeEdges(Tokens.Action.DROP, OUT); // no longer needed in reduce phase
                    context.write(this.longWritable, this.vertexHolder.set('v', value));
                }
            } catch (final Exception e) {
                graph.rollback();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.FAILED_TRANSACTIONS, 1L);
                //context.getCounter(Counters.FAILED_TRANSACTIONS).increment(1l);
                throw new IOException(e.getMessage(), e);
            }

        }

        @Override
        public void cleanup(final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws IOException, InterruptedException {
            try {
                graph.commit();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.SUCCESSFUL_TRANSACTIONS, 1L);
            } catch (Exception e) {
                LOGGER.error("Could not commit transaction during Map.cleanup(): ", e);
                graph.rollback();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.FAILED_TRANSACTIONS, 1L);
                throw new IOException(e.getMessage(), e);
            }
            graph.shutdown();
        }

        public TitanVertex getCreateOrDeleteVertex(final FaunusVertex faunusVertex, final Mapper<NullWritable, FaunusVertex, LongWritable, Holder<FaunusVertex>>.Context context) throws InterruptedException {
            if (this.trackState && faunusVertex.isRemoved()) {
                final Vertex titanVertex = graph.getVertex(faunusVertex.getLongId());
                if (null == titanVertex)
                    HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.NULL_VERTICES_IGNORED, 1L);
                else {
                    titanVertex.remove();
                    HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.VERTICES_REMOVED, 1L);
                }
                return null;
            } else {
                final TitanVertex titanVertex;
                if (faunusVertex.isNew()) {
                    VertexLabel titanLabel = BaseVertexLabel.DEFAULT_VERTEXLABEL;
                    FaunusVertexLabel faunusLabel = faunusVertex.getVertexLabel();
                    if (!faunusLabel.isDefault()) titanLabel = graph.getVertexLabel(faunusLabel.getName());
                    titanVertex = graph.addVertex(titanLabel);
                    HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.VERTICES_ADDED, 1L);
                } else {
                    titanVertex = (TitanVertex) graph.getVertex(faunusVertex.getLongId());
                    if (titanVertex==null) {
                        HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.NULL_VERTICES_IGNORED, 1L);
                        return null;
                    }
                }
                if (faunusVertex.isNew() || faunusVertex.isModified()) {
                    //Synchronize properties
                    for (final TitanProperty p : faunusVertex.query().queryAll().properties()) {
                        getCreateOrDeleteRelation(graph,trackState,OUT,faunusVertex,titanVertex,
                                (StandardFaunusProperty)p,context);
                    }
                }
                return titanVertex;
            }
        }
    }

    private static TitanRelation getCreateOrDeleteRelation(final TitanGraph graph, final boolean trackState, final Direction dir,
                                             final FaunusVertex faunusVertex, final TitanVertex titanVertex,
                                             final StandardFaunusRelation faunusRelation, final Mapper.Context context) {
        assert dir==IN || dir==OUT;
        Map<Long, Long> idMap = faunusVertex.getProperty(ID_MAP_KEY);
        if (idMap==null) idMap = ImmutableMap.of();

        final TitanRelation titanRelation;
        if (trackState && (faunusRelation.isModified() || faunusRelation.isRemoved())) { //Modify existing
            titanRelation = getIncidentRelation(graph, dir, titanVertex, faunusRelation,
                    faunusRelation.isEdge()?idMap.get(((FaunusEdge)faunusRelation).getVertexId(dir.opposite())):null);
            if (null == titanRelation) {
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.NULL_RELATIONS_IGNORED, 1L);
                //context.getCounter(Counters.NULL_EDGES_IGNORED).increment(1l);
                return null;
            } else if (faunusRelation.isRemoved()) {
                titanRelation.remove();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context,
                        faunusRelation.isEdge()?Counters.EDGES_REMOVED:Counters.VERTEX_PROPERTIES_REMOVED, 1L);
                //context.getCounter(Counters.EDGES_REMOVED).increment(1l);
                return null;
            }
        } else if (trackState && faunusRelation.isLoaded()) {
            return null;
        } else { //Create new
            assert faunusRelation.isNew();
            if (faunusRelation.isEdge()) {
                StandardFaunusEdge faunusEdge = (StandardFaunusEdge)faunusRelation;
                Long othervertexid = faunusEdge.getVertexId(dir.opposite());
                if (idMap.containsKey(othervertexid)) othervertexid=idMap.get(othervertexid);
                TitanVertex otherVertex = (TitanVertex)graph.getVertex(othervertexid);
                //TODO: check that other vertex has valid id assignment for unidirected edges
                if (dir==IN) {
                    titanRelation = otherVertex.addEdge(faunusEdge.getLabel(), titanVertex);
                } else {
                    titanRelation = titanVertex.addEdge(faunusEdge.getLabel(), otherVertex);
                }
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.EDGES_ADDED, 1L);
            } else {
                StandardFaunusProperty faunusProperty = (StandardFaunusProperty)faunusRelation;
                assert dir==OUT;
                titanRelation = titanVertex.addProperty(faunusProperty.getTypeName(),faunusProperty.getValue());
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.VERTEX_PROPERTIES_ADDED, 1L);
            }
        }

        if (faunusRelation.isModified()  || faunusRelation.isNew()) { //Synchronize incident properties + unidirected edges
            for (TitanRelation faunusProp : faunusRelation.query().queryAll().relations()) {
                if (faunusProp.isRemoved()) {
                    titanRelation.removeProperty(faunusProp.getType().getName());
                    HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.EDGE_PROPERTIES_REMOVED, 1L);
                }
            }
            for (TitanRelation faunusProp : faunusRelation.query().queryAll().relations()) {
                if (faunusProp.isNew()) {
                    Object value;
                    if (faunusProp.isProperty()) {
                        value = ((FaunusProperty)faunusProp).getValue();
                    } else {
                        //TODO: ensure that the adjacent vertex has been previous assigned an id since ids don't propagate along unidirected edges
                        value = graph.getVertex(((FaunusEdge)faunusProp).getVertexId(IN));
                    }
                    titanRelation.setProperty(faunusProp.getType().getName(),value);
                    HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.EDGE_PROPERTIES_ADDED, 1L);
                }
            }

        }
        return titanRelation;
    }

    private static TitanRelation getIncidentRelation(final TitanGraph graph, final Direction dir,
                                         final TitanVertex titanVertex, StandardFaunusRelation faunusRelation, Long otherTitanVertexId) {
        TitanVertexQuery qb = titanVertex.query().direction(dir).types(graph.getRelationType(faunusRelation.getTypeName()));
        if (faunusRelation.isEdge()) {
            if (otherTitanVertexId!=null) {
                qb.adjacent((TitanVertex)graph.getVertex(otherTitanVertexId));
            } else {
                StandardFaunusEdge edge = (StandardFaunusEdge)faunusRelation;
                qb.adjacent((TitanVertex) graph.getVertex(edge.getVertexId(dir.opposite())));
            }
        }
        qb.has(ImplicitKey.TITANID.getName(), Cmp.EQUAL, faunusRelation.getLongId());
        TitanRelation titanRelation = (TitanRelation)Iterables.getFirst(qb.relations(),null);
        assert titanRelation==null || titanRelation.getLongId()==faunusRelation.getLongId();
        return titanRelation;
    }

    //MAPS FAUNUS VERTEXIDs to TITAN VERTEXIDs
    public static class Reduce extends Reducer<LongWritable, Holder<FaunusVertex>, NullWritable, FaunusVertex> {

        @Override
        public void reduce(final LongWritable key, final Iterable<Holder<FaunusVertex>> values, final Reducer<LongWritable, Holder<FaunusVertex>, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            FaunusVertex faunusVertex = null;
            // generate a map of the Titan/Hadoop id with the Titan id for all shell vertices (vertices incoming adjacent)
            final java.util.Map<Long, Object> idMap = new HashMap<Long, Object>();
            for (final Holder<FaunusVertex> holder : values) {
                if (holder.getTag() == 's') {
                    idMap.put(holder.get().getLongId(), holder.get().getProperty(TITAN_ID));
                } else {
                    faunusVertex = holder.get();
                }
            }
            if (null != faunusVertex) {
                faunusVertex.setProperty(ID_MAP_KEY, idMap);
                context.write(NullWritable.get(), faunusVertex);
            } else {
                LOGGER.warn("No source vertex: hadoopVertex[" + key.get() + "]");
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.NULL_VERTICES_IGNORED, 1L);
                //context.getCounter(Counters.NULL_VERTICES_IGNORED).increment(1l);
            }
        }
    }

    // WRITE ALL THE EDGES CONNECTING THE VERTICES
    public static class EdgeMap extends Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex> {

        TitanGraph graph;
        boolean trackState;

        @Override
        public void setup(final Mapper.Context context) throws IOException, InterruptedException {
            this.graph = TitanGraphOutputMapReduce.generateGraph(context.getConfiguration());
            this.trackState = context.getConfiguration().getBoolean(Tokens.TITAN_HADOOP_PIPELINE_TRACK_STATE, false);
            LOGGER.setLevel(Level.INFO);
        }

        @Override
        public void map(final NullWritable key, final FaunusVertex value, final Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            try {
                for (final TitanEdge edge : value.query().queryAll().direction(IN).titanEdges()) {
                    this.getCreateOrDeleteEdge(value, (StandardFaunusEdge)edge, context);
                }
            } catch (final Exception e) {
                graph.rollback();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.FAILED_TRANSACTIONS, 1L);
                throw new IOException(e.getMessage(), e);
            }
        }

        @Override
        public void cleanup(final Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex>.Context context) throws IOException, InterruptedException {
            try {
                graph.commit();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.SUCCESSFUL_TRANSACTIONS, 1L);
            } catch (Exception e) {
                LOGGER.error("Could not commit transaction during Reduce.cleanup(): ", e);
                graph.rollback();
                HadoopCompatLoader.getDefaultCompat().incrementContextCounter(context, Counters.FAILED_TRANSACTIONS, 1L);
                throw new IOException(e.getMessage(), e);
            }
            graph.shutdown();
        }

        public TitanEdge getCreateOrDeleteEdge(final FaunusVertex faunusVertex, final StandardFaunusEdge faunusEdge, final Mapper<NullWritable, FaunusVertex, NullWritable, FaunusVertex>.Context context) throws InterruptedException {
            final TitanVertex titanVertex = (TitanVertex) this.graph.getVertex(faunusVertex.getProperty(TITAN_ID));
            return (TitanEdge)getCreateOrDeleteRelation(graph,trackState,IN,faunusVertex,titanVertex,faunusEdge,context);
        }

    }
}