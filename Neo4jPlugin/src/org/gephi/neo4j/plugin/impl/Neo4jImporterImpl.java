/*
Copyright 2008-2010 Gephi
Authors : Martin Škurla
Website : http://www.gephi.org

This file is part of Gephi.

Gephi is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

Gephi is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with Gephi.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.gephi.neo4j.plugin.impl;

import java.util.Collection;
import java.util.Collections;
import org.gephi.neo4j.plugin.api.FilterDescription;
import org.gephi.neo4j.plugin.api.Neo4jImporter;
import org.gephi.neo4j.plugin.api.RelationshipDescription;
import org.gephi.neo4j.plugin.api.TraversalOrder;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.utils.longtask.spi.LongTask;
import org.gephi.utils.progress.Progress;
import org.gephi.utils.progress.ProgressTicket;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.traversal.PruneEvaluator;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.kernel.Traversal;
import org.neo4j.remote.RemoteGraphDatabase;
import org.openide.util.Lookup;
import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;

@ServiceProvider(service = Neo4jImporter.class)
public final class Neo4jImporterImpl implements Neo4jImporter, LongTask {
    // when we want to iterate through whole graph

    private static final int NO_START_NODE = -1;
    private GraphDatabaseService graphDB;
    private GraphModelImportConverter graphModelImportConverter;
    private Traverser traverser;
    private NodeReturnFilter nodeReturnFilter;
    private ProgressTicket progressTicket;
    private boolean cancelImport;

    @Override
    public boolean cancel() {
        cancelImport = true;
        return true;
    }

    @Override
    public void setProgressTicket(ProgressTicket progressTicket) {
        cancelImport = false;
        this.progressTicket = progressTicket;
    }

    @Override
    public void importDatabase(GraphDatabaseService graphDB) {
        importDatabase(graphDB, NO_START_NODE, TraversalOrder.DEPTH_FIRST, Integer.MAX_VALUE);
    }

    @Override
    public void importDatabase(GraphDatabaseService graphDB, Collection<FilterDescription> filterDescriptions, boolean restrictMode, boolean matchCase) {
        importDatabase(graphDB, NO_START_NODE, TraversalOrder.DEPTH_FIRST, Integer.MAX_VALUE, Collections.<RelationshipDescription>emptyList(),
                filterDescriptions, restrictMode, matchCase);
    }

    @Override
    public void importDatabase(GraphDatabaseService graphDB, long startNodeId, TraversalOrder order, int maxDepth) {
        importDatabase(graphDB, startNodeId, order, maxDepth, Collections.<RelationshipDescription>emptyList());
    }

    @Override
    public void importDatabase(GraphDatabaseService graphDB, long startNodeId, TraversalOrder order, int maxDepth,
            Collection<RelationshipDescription> relationshipDescriptions) {
        // last 2 boolean parameters are not important, because if we pass empty collection of filter descriptions, they
        // are not needed
        importDatabase(graphDB, startNodeId, order, maxDepth, relationshipDescriptions, Collections.<FilterDescription>emptyList(),
                false, false);
    }

    @Override
    public void importDatabase(GraphDatabaseService graphDB, long startNodeId, TraversalOrder order, int maxDepth,
            Collection<RelationshipDescription> relationshipDescriptions, Collection<FilterDescription> filterDescriptions,
            boolean restrictMode, boolean matchCase) {
        this.graphDB = graphDB;

        String longTaskMessage = (graphDB instanceof RemoteGraphDatabase)
                ? NbBundle.getMessage(Neo4jImporterImpl.class, "CTL_Neo4j_RemoteImportMessage")
                : NbBundle.getMessage(Neo4jImporterImpl.class, "CTL_Neo4j_LocalImportMessage");

        Progress.setDisplayName(progressTicket, longTaskMessage);
        Progress.start(progressTicket);

        if (startNodeId != NO_START_NODE) {
            TraversalDescription traversalDescription = Traversal.description();
            PruneEvaluator pruneEvaluator = Traversal.pruneAfterDepth(maxDepth);

            traversalDescription = order.update(traversalDescription);

            for (RelationshipDescription relationshipDescription : relationshipDescriptions) {
                traversalDescription = traversalDescription.relationships(relationshipDescription.getRelationshipType(),
                        relationshipDescription.getDirection());
            }

            if (!filterDescriptions.isEmpty()) {
                traversalDescription = traversalDescription.filter(new NodeReturnFilter(filterDescriptions,
                        restrictMode,
                        matchCase));
            }

            traverser = traversalDescription.prune(pruneEvaluator).traverse(graphDB.getNodeById(startNodeId));
        } else if (startNodeId == NO_START_NODE && filterDescriptions.size() > 0) {
            nodeReturnFilter = new NodeReturnFilter(filterDescriptions, restrictMode, matchCase);
            traverser = null;
        } else {
            traverser = null;
        }

        doImport();
    }

    private void doImport() {
        Transaction transaction = graphDB.beginTx();
        try {
            importGraph();
            transaction.success();
        } finally {
            transaction.finish();
        }

        Progress.finish(progressTicket);
    }

    private void importGraph() {
        initProject();

        graphModelImportConverter = GraphModelImportConverter.getInstance(graphDB);
        graphModelImportConverter.createNeo4jRelationshipTypeGephiColumn();

        if (traverser == null) {
            importNodes(graphDB.getAllNodes());

            for (org.neo4j.graphdb.Node node : graphDB.getAllNodes()) {
                importRelationships(node.getRelationships(Direction.INCOMING));
            }
        } else {
            importNodes(traverser.nodes());
            importRelationships(traverser.relationships());
        }
    }

    private void importNodes(Iterable<org.neo4j.graphdb.Node> nodes) {
        for (org.neo4j.graphdb.Node node : nodes) {
            if (cancelImport) {
                return;
            }

            if (nodeReturnFilter != null) {
                if (nodeReturnFilter.accept(node)) {
                    processNode(node);
                }
            } else {
                processNode(node);
            }
        }
    }

    private void processNode(org.neo4j.graphdb.Node node) {
        graphModelImportConverter.createGephiNodeFromNeoNode(node);
    }

    private void importRelationships(Iterable<Relationship> relationships) {
        for (Relationship relationship : relationships) {
            if (cancelImport) {
                return;
            }

            processRelationship(relationship);
        }
    }

    private void processRelationship(Relationship neoRelationship) {
        graphModelImportConverter.createGephiEdge(neoRelationship);
    }

    private void initProject() {
        ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);

        if (pc.getCurrentProject() == null) {
            pc.newProject();
        }
    }
}
