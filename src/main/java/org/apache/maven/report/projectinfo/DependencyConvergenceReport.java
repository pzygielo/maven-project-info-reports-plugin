/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.report.projectinfo;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkEventAttributes;
import org.apache.maven.doxia.sink.impl.SinkEventAttributeSet;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.report.projectinfo.dependencies.DependencyVersionMap;
import org.apache.maven.report.projectinfo.dependencies.SinkSerializingDependencyNodeVisitor;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.AncestorOrSelfDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.codehaus.plexus.i18n.I18N;

/**
 * Generates the Project Dependency Convergence report for (reactor) builds.
 *
 * @author <a href="mailto:joakim@erdfelt.com">Joakim Erdfelt</a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton </a>
 * @author <a href="mailto:wangyf2010@gmail.com">Simon Wang </a>
 * @since 2.0
 */
@Mojo(name = "dependency-convergence", aggregator = true)
public class DependencyConvergenceReport extends AbstractProjectInfoReport {
    /**
     * URL for the 'icon_success_sml.gif' image
     */
    private static final String IMG_SUCCESS_URL = "images/icon_success_sml.gif";

    /**
     * URL for the 'icon_error_sml.gif' image
     */
    private static final String IMG_ERROR_URL = "images/icon_error_sml.gif";

    private static final int FULL_CONVERGENCE = 100;

    private ArtifactFilter filter = null;

    private Map<MavenProject, DependencyNode> projectMap = new HashMap<>();

    // ----------------------------------------------------------------------
    // Mojo parameters
    // ----------------------------------------------------------------------

    /**
     * Raw dependency collector builder, will use it to build dependency tree.
     */
    private final DependencyCollectorBuilder dependencyCollectorBuilder;

    @Inject
    protected DependencyConvergenceReport(
            RepositorySystem repositorySystem,
            I18N i18n,
            ProjectBuilder projectBuilder,
            DependencyCollectorBuilder dependencyCollectorBuilder) {
        super(repositorySystem, i18n, projectBuilder);
        this.dependencyCollectorBuilder = dependencyCollectorBuilder;
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public String getOutputName() {
        return "dependency-convergence";
    }

    @Override
    protected String getI18Nsection() {
        return "dependency-convergence";
    }

    // ----------------------------------------------------------------------
    // Protected methods
    // ----------------------------------------------------------------------

    @Override
    protected void executeReport(Locale locale) throws MavenReportException {
        try (Sink sink = getSink()) {
            sink.head();
            sink.title();

            if (isReactorBuild()) {
                sink.text(getI18nString(locale, "reactor.title"));
            } else {
                sink.text(getI18nString(locale, "title"));
            }

            sink.title_();
            sink.head_();

            sink.body();

            sink.section1();

            sink.sectionTitle1();

            if (isReactorBuild()) {
                sink.text(getI18nString(locale, "reactor.title"));
            } else {
                sink.text(getI18nString(locale, "title"));
            }

            sink.sectionTitle1_();

            DependencyAnalyzeResult dependencyResult = analyzeDependencyTree();
            int convergence = calculateConvergence(dependencyResult);

            if (convergence < FULL_CONVERGENCE) {
                // legend
                generateLegend(locale, sink);
                sink.lineBreak();
            }

            // stats
            generateStats(locale, sink, dependencyResult);

            sink.section1_();

            if (convergence < FULL_CONVERGENCE) {
                // convergence
                generateConvergence(locale, sink, dependencyResult);
            }

            sink.body_();
        }
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Get snapshots dependencies from all dependency map.
     *
     * @param dependencyMap
     * @return snapshots dependencies
     */
    private List<ReverseDependencyLink> getSnapshotDependencies(
            Map<String, List<ReverseDependencyLink>> dependencyMap) {
        List<ReverseDependencyLink> snapshots = new ArrayList<>();
        for (Map.Entry<String, List<ReverseDependencyLink>> entry : dependencyMap.entrySet()) {
            List<ReverseDependencyLink> depList = entry.getValue();
            Map<String, List<ReverseDependencyLink>> artifactMap = getSortedUniqueArtifactMap(depList);
            for (Map.Entry<String, List<ReverseDependencyLink>> artEntry : artifactMap.entrySet()) {
                String version = artEntry.getKey();
                boolean isReactorProject = false;

                Iterator<ReverseDependencyLink> iterator = artEntry.getValue().iterator();
                // It if enough to check just the first dependency here, because
                // the dependency is the same in all the RDLs in the List. It's the
                // reactorProjects that are different.
                ReverseDependencyLink rdl = null;
                if (iterator.hasNext()) {
                    rdl = iterator.next();
                    if (isReactorProject(rdl.getDependency())) {
                        isReactorProject = true;
                    }
                }

                if (version.endsWith("-SNAPSHOT") && !isReactorProject && rdl != null) {
                    snapshots.add(rdl);
                }
            }
        }

        return snapshots;
    }

    /**
     * Generate the convergence table for all dependencies
     *
     * @param locale
     * @param sink
     * @param result
     */
    private void generateConvergence(Locale locale, Sink sink, DependencyAnalyzeResult result) {
        sink.section2();

        sink.sectionTitle2();

        if (isReactorBuild()) {
            sink.text(getI18nString(locale, "convergence.caption"));
        } else {
            sink.text(getI18nString(locale, "convergence.single.caption"));
        }

        sink.sectionTitle2_();

        // print conflicting dependencies
        for (Map.Entry<String, List<ReverseDependencyLink>> entry :
                result.getConflicting().entrySet()) {
            String key = entry.getKey();
            List<ReverseDependencyLink> depList = entry.getValue();

            sink.section3();
            sink.sectionTitle3();
            sink.text(key);
            sink.sectionTitle3_();

            generateDependencyDetails(locale, sink, depList);

            sink.section3_();
        }

        // print out snapshots jars
        for (ReverseDependencyLink dependencyLink : result.getSnapshots()) {
            sink.section3();
            sink.sectionTitle3();

            Dependency dep = dependencyLink.getDependency();

            sink.text(dep.getGroupId() + ":" + dep.getArtifactId());
            sink.sectionTitle3_();

            List<ReverseDependencyLink> depList = new ArrayList<>();
            depList.add(dependencyLink);
            generateDependencyDetails(locale, sink, depList);

            sink.section3_();
        }

        sink.section2_();
    }

    /**
     * Generate the detail table for a given dependency
     *
     * @param sink
     * @param depList
     */
    private void generateDependencyDetails(Locale locale, Sink sink, List<ReverseDependencyLink> depList) {
        sink.table();
        sink.tableRows();

        Map<String, List<ReverseDependencyLink>> artifactMap = getSortedUniqueArtifactMap(depList);

        sink.tableRow();

        sink.tableCell();

        iconError(locale, sink);

        sink.tableCell_();

        sink.tableCell();

        sink.table();
        sink.tableRows();

        for (String version : artifactMap.keySet()) {
            sink.tableRow();
            sink.tableCell(new SinkEventAttributeSet(SinkEventAttributes.WIDTH, "25%"));
            sink.text(version);
            sink.tableCell_();

            sink.tableCell();
            generateVersionDetails(sink, artifactMap, version);
            sink.tableCell_();

            sink.tableRow_();
        }
        sink.tableRows_();
        sink.table_();

        sink.tableCell_();

        sink.tableRow_();

        sink.tableRows_();
        sink.table_();
    }

    /**
     * Generate version details for a given dependency
     *
     * @param sink
     * @param artifactMap
     * @param version
     */
    private void generateVersionDetails(
            Sink sink, Map<String, List<ReverseDependencyLink>> artifactMap, String version) {
        sink.numberedList(0); // Use lower alpha numbering
        List<ReverseDependencyLink> depList = artifactMap.get(version);

        List<DependencyNode> projectNodes = getProjectNodes(depList);

        if (projectNodes.isEmpty()) {
            getLog().warn("Can't find project nodes for dependency list: "
                    + depList.get(0).getDependency());
            return;
        }
        Collections.sort(projectNodes, new DependencyNodeComparator());

        for (DependencyNode projectNode : projectNodes) {
            if (isReactorBuild()) {
                sink.numberedListItem();
            }

            showVersionDetails(projectNode, depList, sink);

            if (isReactorBuild()) {
                sink.numberedListItem_();
            }

            sink.lineBreak();
        }

        sink.numberedList_();
    }

    private List<DependencyNode> getProjectNodes(List<ReverseDependencyLink> depList) {
        List<DependencyNode> projectNodes = new ArrayList<>();

        for (ReverseDependencyLink depLink : depList) {
            MavenProject project = depLink.getProject();
            DependencyNode projectNode = this.projectMap.get(project);

            if (projectNode != null && !projectNodes.contains(projectNode)) {
                projectNodes.add(projectNode);
            }
        }
        return projectNodes;
    }

    private void showVersionDetails(DependencyNode projectNode, List<ReverseDependencyLink> depList, Sink sink) {
        if (depList == null || depList.isEmpty()) {
            return;
        }

        Dependency dependency = depList.get(0).getDependency();
        String key = dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + dependency.getType() + ":"
                + dependency.getVersion();

        serializeDependencyTree(projectNode, key, sink);
    }

    /**
     * Serializes the specified dependency tree to a string.
     *
     * @param rootNode the dependency tree root node to serialize
     * @return the serialized dependency tree
     */
    private void serializeDependencyTree(DependencyNode rootNode, String key, Sink sink) {
        DependencyNodeVisitor visitor = getSerializingDependencyNodeVisitor(sink);

        visitor = new BuildingDependencyNodeVisitor(visitor);

        DependencyNodeFilter nodeFilter = createDependencyNodeFilter(key);

        if (nodeFilter != null) {
            CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
            DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor(collectingVisitor, nodeFilter);
            rootNode.accept(firstPassVisitor);

            DependencyNodeFilter secondPassFilter =
                    new AncestorOrSelfDependencyNodeFilter(collectingVisitor.getNodes());
            visitor = new FilteringDependencyNodeVisitor(visitor, secondPassFilter);
        }

        rootNode.accept(visitor);
    }

    /**
     * Gets the dependency node filter to use when serializing the dependency graph.
     *
     * @return the dependency node filter, or <code>null</code> if none required
     */
    private DependencyNodeFilter createDependencyNodeFilter(String includes) {
        List<DependencyNodeFilter> filters = new ArrayList<>();

        // filter includes
        if (includes != null) {
            List<String> patterns = Arrays.asList(includes.split(","));

            getLog().debug("+ Filtering dependency tree by artifact include patterns: " + patterns);

            ArtifactFilter artifactFilter = new StrictPatternIncludesArtifactFilter(patterns);
            filters.add(new ArtifactDependencyNodeFilter(artifactFilter));
        }

        return filters.isEmpty() ? null : new AndDependencyNodeFilter(filters);
    }

    /**
     * @param sink {@link Sink}
     * @return {@link DependencyNodeVisitor}
     */
    public DependencyNodeVisitor getSerializingDependencyNodeVisitor(Sink sink) {
        return new SinkSerializingDependencyNodeVisitor(sink);
    }

    /**
     * Produce a Map of relationships between dependencies (its version) and reactor projects. This is the structure of
     * the Map:
     *
     * <pre>
     * +--------------------+----------------------------------+
     * | key                | value                            |
     * +--------------------+----------------------------------+
     * | version of a       | A List of ReverseDependencyLinks |
     * | dependency         | which each look like this:       |
     * |                    | +------------+-----------------+ |
     * |                    | | dependency | reactor project | |
     * |                    | +------------+-----------------+ |
     * +--------------------+----------------------------------+
     * </pre>
     *
     * @return A Map of sorted unique artifacts
     */
    private Map<String, List<ReverseDependencyLink>> getSortedUniqueArtifactMap(List<ReverseDependencyLink> depList) {
        Map<String, List<ReverseDependencyLink>> uniqueArtifactMap = new TreeMap<>();

        for (ReverseDependencyLink rdl : depList) {
            String key = rdl.getDependency().getVersion();
            List<ReverseDependencyLink> projectList = uniqueArtifactMap.get(key);
            if (projectList == null) {
                projectList = new ArrayList<>();
            }
            projectList.add(rdl);
            uniqueArtifactMap.put(key, projectList);
        }

        return uniqueArtifactMap;
    }

    /**
     * Generate the legend table
     *
     * @param locale
     * @param sink
     */
    private void generateLegend(Locale locale, Sink sink) {
        sink.table();
        sink.tableRows();
        sink.tableCaption();
        sink.bold();
        sink.text(getI18nString(locale, "legend"));
        sink.bold_();
        sink.tableCaption_();

        sink.tableRow();

        sink.tableCell();
        iconError(locale, sink);
        sink.tableCell_();
        sink.tableCell();
        sink.text(getI18nString(locale, "legend.different"));
        sink.tableCell_();

        sink.tableRow_();

        sink.tableRows_();
        sink.table_();
    }

    /**
     * Generate the statistic table
     *
     * @param locale
     * @param sink
     * @param result
     */
    private void generateStats(Locale locale, Sink sink, DependencyAnalyzeResult result) {
        int depCount = result.getDependencyCount();

        int artifactCount = result.getArtifactCount();
        int snapshotCount = result.getSnapshotCount();
        int conflictingCount = result.getConflictingCount();

        int convergence = calculateConvergence(result);

        // Create report
        sink.table();
        sink.tableRows();
        sink.tableCaption();
        sink.bold();
        sink.text(getI18nString(locale, "stats.caption"));
        sink.bold_();
        sink.tableCaption_();

        if (isReactorBuild()) {
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text(getI18nString(locale, "stats.modules"));
            sink.tableHeaderCell_();
            sink.tableCell();
            sink.text(String.valueOf(reactorProjects.size()));
            sink.tableCell_();
            sink.tableRow_();
        }

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text(getI18nString(locale, "stats.dependencies"));
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text(String.valueOf(depCount));
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text(getI18nString(locale, "stats.artifacts"));
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text(String.valueOf(artifactCount));
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text(getI18nString(locale, "stats.conflicting"));
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text(String.valueOf(conflictingCount));
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text(getI18nString(locale, "stats.snapshots"));
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text(String.valueOf(snapshotCount));
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text(getI18nString(locale, "stats.convergence"));
        sink.tableHeaderCell_();
        sink.tableCell();
        if (convergence < FULL_CONVERGENCE) {
            iconError(locale, sink);
        } else {
            iconSuccess(locale, sink);
        }
        sink.nonBreakingSpace();
        sink.bold();
        sink.text(String.valueOf(convergence) + " %");
        sink.bold_();
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text(getI18nString(locale, "stats.readyrelease"));
        sink.tableHeaderCell_();
        sink.tableCell();
        if (convergence >= FULL_CONVERGENCE && snapshotCount <= 0) {
            iconSuccess(locale, sink);
            sink.nonBreakingSpace();
            sink.bold();
            sink.text(getI18nString(locale, "stats.readyrelease.success"));
            sink.bold_();
        } else {
            iconError(locale, sink);
            sink.nonBreakingSpace();
            sink.bold();
            sink.text(getI18nString(locale, "stats.readyrelease.error"));
            sink.bold_();
            if (convergence < FULL_CONVERGENCE) {
                sink.lineBreak();
                sink.text(getI18nString(locale, "stats.readyrelease.error.convergence"));
            }
            if (snapshotCount > 0) {
                sink.lineBreak();
                sink.text(getI18nString(locale, "stats.readyrelease.error.snapshots"));
            }
        }
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRows_();
        sink.table_();
    }

    /**
     * Check to see if the specified dependency is among the reactor projects.
     *
     * @param dependency The dependency to check
     * @return true if and only if the dependency is a reactor project
     */
    private boolean isReactorProject(Dependency dependency) {
        for (MavenProject mavenProject : reactorProjects) {
            if (mavenProject.getGroupId().equals(dependency.getGroupId())
                    && mavenProject.getArtifactId().equals(dependency.getArtifactId())) {
                if (getLog().isDebugEnabled()) {
                    getLog().debug(dependency + " is a reactor project");
                }
                return true;
            }
        }
        return false;
    }

    private boolean isReactorBuild() {
        return this.reactorProjects.size() > 1;
    }

    private void iconSuccess(Locale locale, Sink sink) {
        SinkEventAttributes attributes =
                new SinkEventAttributeSet(SinkEventAttributes.ALT, getI18nString(locale, "icon.success"));
        sink.figureGraphics(IMG_SUCCESS_URL, attributes);
    }

    private void iconError(Locale locale, Sink sink) {
        SinkEventAttributes attributes =
                new SinkEventAttributeSet(SinkEventAttributes.ALT, getI18nString(locale, "icon.error"));
        sink.figureGraphics(IMG_ERROR_URL, attributes);
    }

    /**
     * Produce a DependencyAnalyzeResult, it contains conflicting dependencies map, snapshot dependencies map and all
     * dependencies map. Map structure is the relationships between dependencies (its groupId:artifactId) and reactor
     * projects. This is the structure of the Map:
     *
     * <pre>
     * +--------------------+----------------------------------+---------------|
     * | key                | value                                            |
     * +--------------------+----------------------------------+---------------|
     * | groupId:artifactId | A List of ReverseDependencyLinks                 |
     * | of a dependency    | which each look like this:                       |
     * |                    | +------------+-----------------+-----------------|
     * |                    | | dependency | reactor project | dependency node |
     * |                    | +------------+-----------------+-----------------|
     * +--------------------+--------------------------------------------------|
     * </pre>
     *
     * @return DependencyAnalyzeResult contains conflicting dependencies map, snapshot dependencies map and all
     * dependencies map.
     * @throws MavenReportException
     */
    private DependencyAnalyzeResult analyzeDependencyTree() throws MavenReportException {
        Map<String, List<ReverseDependencyLink>> conflictingDependencyMap = new TreeMap<>();
        Map<String, List<ReverseDependencyLink>> allDependencies = new TreeMap<>();

        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(getSession().getProjectBuildingRequest());

        for (MavenProject reactorProject : reactorProjects) {
            buildingRequest.setProject(reactorProject);

            DependencyNode node = getNode(buildingRequest);

            this.projectMap.put(reactorProject, node);

            getConflictingDependencyMap(conflictingDependencyMap, reactorProject, node);

            getAllDependencyMap(allDependencies, reactorProject, node);
        }

        return populateDependencyAnalyzeResult(conflictingDependencyMap, allDependencies);
    }

    /**
     * Produce DependencyAnalyzeResult base on conflicting dependencies map, all dependencies map.
     *
     * @param conflictingDependencyMap
     * @param allDependencies
     * @return DependencyAnalyzeResult contains conflicting dependencies map, snapshot dependencies map and all
     * dependencies map.
     */
    private DependencyAnalyzeResult populateDependencyAnalyzeResult(
            Map<String, List<ReverseDependencyLink>> conflictingDependencyMap,
            Map<String, List<ReverseDependencyLink>> allDependencies) {
        DependencyAnalyzeResult dependencyResult = new DependencyAnalyzeResult();

        dependencyResult.setAll(allDependencies);
        dependencyResult.setConflicting(conflictingDependencyMap);

        List<ReverseDependencyLink> snapshots = getSnapshotDependencies(allDependencies);
        dependencyResult.setSnapshots(snapshots);
        return dependencyResult;
    }

    /**
     * Get conflicting dependency map base on specified dependency node.
     *
     * @param conflictingDependencyMap
     * @param reactorProject
     * @param node
     */
    private void getConflictingDependencyMap(
            Map<String, List<ReverseDependencyLink>> conflictingDependencyMap,
            MavenProject reactorProject,
            DependencyNode node) {
        DependencyVersionMap visitor = new DependencyVersionMap();
        visitor.setUniqueVersions(true);

        node.accept(visitor);

        for (List<DependencyNode> nodes : visitor.getConflictedVersionNumbers()) {
            DependencyNode dependencyNode = nodes.get(0);

            String key = dependencyNode.getArtifact().getGroupId() + ":"
                    + dependencyNode.getArtifact().getArtifactId();

            List<ReverseDependencyLink> dependencyList = conflictingDependencyMap.get(key);
            if (dependencyList == null) {
                dependencyList = new ArrayList<>();
            }

            dependencyList.add(new ReverseDependencyLink(toDependency(dependencyNode.getArtifact()), reactorProject));

            for (DependencyNode workNode : nodes.subList(1, nodes.size())) {
                dependencyList.add(new ReverseDependencyLink(toDependency(workNode.getArtifact()), reactorProject));
            }

            conflictingDependencyMap.put(key, dependencyList);
        }
    }

    /**
     * Get all dependencies (both directive & transitive dependencies) by specified dependency node.
     *
     * @param allDependencies
     * @param reactorProject
     * @param node
     */
    private void getAllDependencyMap(
            Map<String, List<ReverseDependencyLink>> allDependencies,
            MavenProject reactorProject,
            DependencyNode node) {
        Set<Artifact> artifacts = getAllDescendants(node);

        for (Artifact art : artifacts) {
            String key = art.getGroupId() + ":" + art.getArtifactId();

            List<ReverseDependencyLink> reverseDepependencies = allDependencies.get(key);
            if (reverseDepependencies == null) {
                reverseDepependencies = new ArrayList<>();
            }

            if (!containsDependency(reverseDepependencies, art)) {
                reverseDepependencies.add(new ReverseDependencyLink(toDependency(art), reactorProject));
            }

            allDependencies.put(key, reverseDepependencies);
        }
    }

    /**
     * Convert Artifact to Dependency
     *
     * @param artifact
     * @return Dependency object
     */
    private Dependency toDependency(Artifact artifact) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(artifact.getGroupId());
        dependency.setArtifactId(artifact.getArtifactId());
        dependency.setVersion(artifact.getVersion());
        dependency.setClassifier(artifact.getClassifier());
        dependency.setScope(artifact.getScope());

        return dependency;
    }

    /**
     * To check whether dependency list contains a given artifact.
     *
     * @param reverseDependencies
     * @param art
     * @return contains:true; Not contains:false;
     */
    private boolean containsDependency(List<ReverseDependencyLink> reverseDependencies, Artifact art) {

        for (ReverseDependencyLink revDependency : reverseDependencies) {
            Dependency dep = revDependency.getDependency();
            if (dep.getGroupId().equals(art.getGroupId())
                    && dep.getArtifactId().equals(art.getArtifactId())
                    && dep.getVersion().equals(art.getVersion())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get root node of dependency tree for a given project
     *
     * @param buildingRequest
     * @return root node of dependency tree
     * @throws MavenReportException
     */
    private DependencyNode getNode(ProjectBuildingRequest buildingRequest) throws MavenReportException {
        try {
            return dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, filter);
        } catch (DependencyCollectorBuilderException e) {
            throw new MavenReportException("Could not build dependency tree: " + e.getMessage(), e);
        }
    }

    /**
     * Get all descendants nodes for a given dependency node.
     *
     * @param node
     * @return set of descendants artifacts.
     */
    private Set<Artifact> getAllDescendants(DependencyNode node) {
        Set<Artifact> children = null;
        if (node.getChildren() != null) {
            children = new HashSet<>();
            for (DependencyNode depNode : node.getChildren()) {
                children.add(depNode.getArtifact());
                Set<Artifact> subNodes = getAllDescendants(depNode);
                if (subNodes != null) {
                    children.addAll(subNodes);
                }
            }
        }
        return children;
    }

    private int calculateConvergence(DependencyAnalyzeResult result) {
        return (int) (((double) result.getDependencyCount() / (double) result.getArtifactCount()) * FULL_CONVERGENCE);
    }

    /**
     * Internal object
     */
    private static class ReverseDependencyLink {
        private Dependency dependency;

        protected MavenProject project;

        ReverseDependencyLink(Dependency dependency, MavenProject project) {
            this.dependency = dependency;
            this.project = project;
        }

        public Dependency getDependency() {
            return dependency;
        }

        public MavenProject getProject() {
            return project;
        }

        @Override
        public String toString() {
            return project.getId();
        }
    }

    /**
     * Internal ReverseDependencyLink comparator
     */
    static class DependencyNodeComparator implements Comparator<DependencyNode> {
        /**
         * {@inheritDoc}
         */
        public int compare(DependencyNode p1, DependencyNode p2) {
            return p1.getArtifact().getId().compareTo(p2.getArtifact().getId());
        }
    }

    /**
     * Internal object
     */
    private class DependencyAnalyzeResult {
        Map<String, List<ReverseDependencyLink>> all;

        List<ReverseDependencyLink> snapshots;

        Map<String, List<ReverseDependencyLink>> conflicting;

        public void setAll(Map<String, List<ReverseDependencyLink>> all) {
            this.all = all;
        }

        public List<ReverseDependencyLink> getSnapshots() {
            return snapshots;
        }

        public void setSnapshots(List<ReverseDependencyLink> snapshots) {
            this.snapshots = snapshots;
        }

        public Map<String, List<ReverseDependencyLink>> getConflicting() {
            return conflicting;
        }

        public void setConflicting(Map<String, List<ReverseDependencyLink>> conflicting) {
            this.conflicting = conflicting;
        }

        public int getDependencyCount() {
            return all.size();
        }

        public int getSnapshotCount() {
            return this.snapshots.size();
        }

        public int getConflictingCount() {
            return this.conflicting.size();
        }

        public int getArtifactCount() {
            int artifactCount = 0;
            for (List<ReverseDependencyLink> depList : this.all.values()) {
                Map<String, List<ReverseDependencyLink>> artifactMap = getSortedUniqueArtifactMap(depList);
                artifactCount += artifactMap.size();
            }

            return artifactCount;
        }
    }
}
