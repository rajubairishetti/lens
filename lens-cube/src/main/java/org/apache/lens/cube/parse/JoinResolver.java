/**
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
package org.apache.lens.cube.parse;

import static org.apache.hadoop.hive.ql.parse.HiveParser.Identifier;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_FULLOUTERJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_JOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_LEFTOUTERJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_LEFTSEMIJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_RIGHTOUTERJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_SUBQUERY;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_TABNAME;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_TABREF;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_UNIQUEJOIN;

import java.util.*;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.JoinCond;
import org.apache.hadoop.hive.ql.parse.JoinType;
import org.apache.hadoop.hive.ql.parse.QB;
import org.apache.hadoop.hive.ql.parse.QBJoinTree;
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.lens.cube.metadata.AbstractCubeTable;
import org.apache.lens.cube.metadata.CubeDimensionTable;
import org.apache.lens.cube.metadata.CubeInterface;
import org.apache.lens.cube.metadata.CubeMetastoreClient;
import org.apache.lens.cube.metadata.Dimension;
import org.apache.lens.cube.metadata.JoinChain;
import org.apache.lens.cube.metadata.SchemaGraph;
import org.apache.lens.cube.metadata.SchemaGraph.TableRelationship;
import org.apache.lens.cube.parse.CandidateTablePruneCause.CubeTableCause;
import org.apache.lens.cube.parse.CubeQueryContext.OptionalDimCtx;

/**
 * 
 * JoinResolver.
 * 
 */
class JoinResolver implements ContextRewriter {

  private static final Log LOG = LogFactory.getLog(JoinResolver.class);

  @ToString
  public static class JoinClause implements Comparable<JoinClause> {
    private final int cost;
    // all dimensions in path except target
    private final Set<Dimension> dimsInPath;
    private final Map<Dimension, List<TableRelationship>> chain;
    transient Map<AbstractCubeTable, Set<String>> chainColumns = new HashMap<AbstractCubeTable, Set<String>>();

    public JoinClause(Map<Dimension, List<TableRelationship>> chain, Set<Dimension> dimsInPath) {
      this.chain = chain;
      this.cost = dimsInPath.size();
      this.dimsInPath = dimsInPath;
    }

    void initChainColumns() {
      for (List<TableRelationship> path : chain.values()) {
        for (TableRelationship edge : path) {
          Set<String> fcols = chainColumns.get(edge.getFromTable());
          if (fcols == null) {
            fcols = new HashSet<String>();
            chainColumns.put(edge.getFromTable(), fcols);
          }
          fcols.add(edge.getFromColumn());

          Set<String> tocols = chainColumns.get(edge.getToTable());
          if (tocols == null) {
            tocols = new HashSet<String>();
            chainColumns.put(edge.getToTable(), tocols);
          }
          tocols.add(edge.getToColumn());
        }
      }
    }

    public int getCost() {
      return cost;
    }

    @Override
    public int compareTo(JoinClause joinClause) {
      return cost - joinClause.getCost();
    }
  }

  /**
   * Store join chain information resolved by join resolver
   */
  public static class AutoJoinContext {
    // Map of a joined table to list of all possible paths from that table to
    // the target
    private final Map<Dimension, List<SchemaGraph.JoinPath>> allPaths;
    // User supplied partial join conditions
    private final Map<AbstractCubeTable, String> partialJoinConditions;
    // True if the query contains user supplied partial join conditions
    private final boolean partialJoinChains;
    // Map of joined table to the join type (if provided by user)
    private final Map<AbstractCubeTable, JoinType> tableJoinTypeMap;

    // True if joins were resolved automatically
    private boolean joinsResolved;
    // Target table for the auto join resolver
    private final AbstractCubeTable autoJoinTarget;
    // Configuration string to control join type
    private String joinTypeCfg;

    // Map of a joined table to its columns which are part of any of the join
    // paths. This is used in candidate table resolver
    @Getter private Map<Dimension, Map<AbstractCubeTable, List<String>>> joinPathFromColumns =
        new HashMap<Dimension, Map<AbstractCubeTable, List<String>>>();

    @Getter private Map<Dimension, Map<AbstractCubeTable, List<String>>> joinPathToColumns =
        new HashMap<Dimension, Map<AbstractCubeTable, List<String>>>();

    // there can be separate join clause for each fact incase of multi fact queries
    @Getter Map<CandidateFact, JoinClause> factClauses = new HashMap<CandidateFact, JoinClause>();
    @Getter @Setter JoinClause minCostClause;
    public AutoJoinContext(Map<Dimension, List<SchemaGraph.JoinPath>> allPaths,
        Map<Dimension, OptionalDimCtx> optionalDimensions, Map<AbstractCubeTable, String> partialJoinConditions,
        boolean partialJoinChains, Map<AbstractCubeTable, JoinType> tableJoinTypeMap, AbstractCubeTable autoJoinTarget,
        String joinTypeCfg, boolean joinsResolved) {
      this.allPaths = allPaths;
      initJoinPathColumns();
      this.partialJoinConditions = partialJoinConditions;
      this.partialJoinChains = partialJoinChains;
      this.tableJoinTypeMap = tableJoinTypeMap;
      this.autoJoinTarget = autoJoinTarget;
      this.joinTypeCfg = joinTypeCfg;
      this.joinsResolved = joinsResolved;
      LOG.debug("All join paths:" + allPaths);
      LOG.debug("Join path from columns:" + joinPathFromColumns);
      LOG.debug("Join path to columns:" + joinPathToColumns);
    }

    public AbstractCubeTable getAutoJoinTarget() {
      return autoJoinTarget;
    }

    private JoinClause getJoinClause(CandidateFact fact) {
      if (fact == null) {
        return minCostClause;
      }
      return factClauses.get(fact);
    }
    // Populate map of tables to their columns which are present in any of the
    // join paths
    private void initJoinPathColumns() {
      for (List<SchemaGraph.JoinPath> paths : allPaths.values()) {
        for (int i = 0; i < paths.size(); i++) {
          SchemaGraph.JoinPath jp = paths.get(i);
          jp.initColumnsForTable();
        }
      }
      refreshJoinPathColumns();
    }

    public void refreshJoinPathColumns() {
      joinPathFromColumns.clear();
      joinPathToColumns.clear();
      for (Map.Entry<Dimension, List<SchemaGraph.JoinPath>> joinPathEntry : allPaths.entrySet()) {
        List<SchemaGraph.JoinPath> joinPaths = joinPathEntry.getValue();
        Map<AbstractCubeTable, List<String>> fromColPaths = joinPathFromColumns.get(joinPathEntry.getKey());
        Map<AbstractCubeTable, List<String>> toColPaths = joinPathToColumns.get(joinPathEntry.getKey());
        if (fromColPaths == null) {
          fromColPaths = new HashMap<AbstractCubeTable, List<String>>();
          joinPathFromColumns.put(joinPathEntry.getKey(), fromColPaths);
        }

        if (toColPaths == null) {
          toColPaths = new HashMap<AbstractCubeTable, List<String>>();
          joinPathToColumns.put(joinPathEntry.getKey(), toColPaths);
        }
        populateJoinPathCols(joinPaths, fromColPaths, toColPaths);
      }
    }

    private void populateJoinPathCols(List<SchemaGraph.JoinPath> joinPaths,
        Map<AbstractCubeTable, List<String>> fromPathColumns, Map<AbstractCubeTable, List<String>> toPathColumns) {
      for (SchemaGraph.JoinPath path : joinPaths) {
        for (TableRelationship edge : path.getEdges()) {
          AbstractCubeTable fromTable = edge.getFromTable();
          String fromColumn = edge.getFromColumn();
          List<String> columnsOfFromTable = fromPathColumns.get(fromTable);
          if (columnsOfFromTable == null) {
            columnsOfFromTable = new ArrayList<String>();
            fromPathColumns.put(fromTable, columnsOfFromTable);
          }
          columnsOfFromTable.add(fromColumn);

          // Similarly populate for the 'to' table
          AbstractCubeTable toTable = edge.getToTable();
          String toColumn = edge.getToColumn();
          List<String> columnsOfToTable = toPathColumns.get(toTable);
          if (columnsOfToTable == null) {
            columnsOfToTable = new ArrayList<String>();
            toPathColumns.put(toTable, columnsOfToTable);
          }
          columnsOfToTable.add(toColumn);
        }
      }
    }

    public void printAllPaths(String src) {
      LOG.info(src + " All paths" + allPaths);
    }
    public void removeJoinedTable(Dimension dim) {
      allPaths.remove(dim);
      joinPathFromColumns.remove(dim);
    }

    public Map<AbstractCubeTable, String> getPartialJoinConditions() {
      return partialJoinConditions;
    }

    public String getFromString(String fromTable, CandidateFact fact, Set<Dimension> qdims,
        Map<Dimension, CandidateDim> dimsToQuery, CubeQueryContext cubeql) throws SemanticException {
      String fromString = fromTable;
      LOG.info("All paths dump:" + cubeql.getAutoJoinCtx().getAllPaths());
      if (qdims == null || qdims.isEmpty()) {
        return fromString;
      }
      // Compute the merged join clause string for the min cost joinclause
      String clause = getMergedJoinClause(cubeql.getAutoJoinCtx().getJoinClause(fact).chain, dimsToQuery,
          qdims, cubeql);

      fromString += clause;
      return fromString;
    }

    // Some refactoring needed to account for multiple join paths
    public String getMergedJoinClause(Map<Dimension, List<TableRelationship>> joinChain,
        Map<Dimension, CandidateDim> dimsToQuery, Set<Dimension> qdims, CubeQueryContext cubeql) {

      Set<String> clauses = new LinkedHashSet<String>();
      String joinTypeStr = "";
      JoinType joinType = JoinType.INNER;

      // this flag is set to true if user has specified a partial join chain
      if (!partialJoinChains) {
        // User has not specified any join conditions. In this case, we rely on
        // configuration for the join type
        if (StringUtils.isNotBlank(joinTypeCfg)) {
          joinType = JoinType.valueOf(joinTypeCfg.toUpperCase());
          joinTypeStr = getJoinTypeStr(joinType);
        }
      }

      for (Map.Entry<Dimension, List<TableRelationship>> entry : joinChain.entrySet()) {
        List<TableRelationship> chain = entry.getValue();
        Dimension table = entry.getKey();

        // check if join with this dimension is required
        if (!qdims.contains(table)) {
          continue;
        }

        if (partialJoinChains) {
          joinType = tableJoinTypeMap.get(table);
          joinTypeStr = getJoinTypeStr(joinType);
        }

        for (int i = chain.size() - 1; i >= 0; i--) {
          TableRelationship rel = chain.get(i);
          StringBuilder clause = new StringBuilder(joinTypeStr).append(" join ");
          // Add storage table name followed by alias
          clause.append(dimsToQuery.get(rel.getToTable()).getStorageString(
              cubeql.getAliasForTabName(rel.getToTable().getName())));

          clause.append(" on ").append(cubeql.getAliasForTabName(rel.getFromTable().getName())).append(".")
          .append(rel.getFromColumn()).append(" = ").append(cubeql.getAliasForTabName(rel.getToTable().getName()))
          .append(".").append(rel.getToColumn());

          // We have to push user specified filters for the joined tables
          String userFilter = null;
          // Partition condition on the tables also needs to be pushed depending
          // on the join
          String storageFilter = null;

          if (JoinType.INNER == joinType || JoinType.LEFTOUTER == joinType || JoinType.LEFTSEMI == joinType) {
            // For inner and left joins push filter of right table
            userFilter = partialJoinConditions.get(rel.getToTable());
            if (partialJoinConditions.containsKey(rel.getFromTable())) {
              if (StringUtils.isNotBlank(userFilter)) {
                userFilter += (" AND " + partialJoinConditions.get(rel.getFromTable()));
              } else {
                userFilter = partialJoinConditions.get(rel.getFromTable());
              }
            }
            storageFilter = getStorageFilter(dimsToQuery, rel.getToTable());
            dimsToQuery.get(rel.getToTable()).setWhereClauseAdded();
          } else if (JoinType.RIGHTOUTER == joinType) {
            // For right outer joins, push filters of left table
            userFilter = partialJoinConditions.get(rel.getFromTable());
            if (partialJoinConditions.containsKey(rel.getToTable())) {
              if (StringUtils.isNotBlank(userFilter)) {
                userFilter += (" AND " + partialJoinConditions.get(rel.getToTable()));
              } else {
                userFilter = partialJoinConditions.get(rel.getToTable());
              }
            }
            if (rel.getFromTable() instanceof Dimension) {
              storageFilter = getStorageFilter(dimsToQuery, rel.getFromTable());
              dimsToQuery.get(rel.getFromTable()).setWhereClauseAdded();
            }
          } else if (JoinType.FULLOUTER == joinType) {
            // For full outer we need to push filters of both left and right
            // tables in the join clause
            String leftFilter = null, rightFilter = null;
            String leftStorageFilter = null, rightStorgeFilter = null;

            if (StringUtils.isNotBlank(partialJoinConditions.get(rel.getFromTable()))) {
              leftFilter = partialJoinConditions.get(rel.getFromTable()) + " and ";
            }

            if (rel.getFromTable() instanceof Dimension) {
              leftStorageFilter = getStorageFilter(dimsToQuery, rel.getFromTable());
              if (StringUtils.isNotBlank((leftStorageFilter))) {
                dimsToQuery.get(rel.getFromTable()).setWhereClauseAdded();
              }
            }

            if (StringUtils.isNotBlank(partialJoinConditions.get(rel.getToTable()))) {
              rightFilter = partialJoinConditions.get(rel.getToTable());
            }

            rightStorgeFilter = getStorageFilter(dimsToQuery, rel.getToTable());
            if (StringUtils.isNotBlank(rightStorgeFilter)) {
              if (StringUtils.isNotBlank((leftStorageFilter))) {
                leftStorageFilter += " and ";
              }
              dimsToQuery.get(rel.getToTable()).setWhereClauseAdded();
            }

            userFilter = (leftFilter == null ? "" : leftFilter) + (rightFilter == null ? "" : rightFilter);
            storageFilter =
                (leftStorageFilter == null ? "" : leftStorageFilter)
                + (rightStorgeFilter == null ? "" : rightStorgeFilter);
          }

          if (StringUtils.isNotBlank(userFilter)) {
            clause.append(" and ").append(userFilter);
          }
          if (StringUtils.isNotBlank(storageFilter)) {
            clause.append(" and ").append(storageFilter);
          }
          clauses.add(clause.toString());
        }
      }
      return StringUtils.join(clauses, "");
    }

    public Set<Dimension> getDimsOnPath(Map<Dimension, List<TableRelationship>> joinChain, Set<Dimension> qdims) {
      Set<Dimension> dimsOnPath = new HashSet<Dimension>();
      for (Map.Entry<Dimension, List<TableRelationship>> entry : joinChain.entrySet()) {
        List<TableRelationship> chain = entry.getValue();
        Dimension table = entry.getKey();

        // check if join with this dimension is required
        if (!qdims.contains(table)) {
          continue;
        }

        for (int i = chain.size() - 1; i >= 0; i--) {
          TableRelationship rel = chain.get(i);
          dimsOnPath.add((Dimension)rel.getToTable());
        }
      }
      return dimsOnPath;
    }

    private String getStorageFilter(Map<Dimension, CandidateDim> dimsToQuery, AbstractCubeTable table) {
      String whereClause = "";
      if (dimsToQuery != null && dimsToQuery.get(table) != null) {
        if (StringUtils.isNotBlank(dimsToQuery.get(table).whereClause)) {
          whereClause = dimsToQuery.get(table).whereClause;
        }
      }
      return whereClause;
    }

    /**
     * @return the joinsResolved
     */
    public boolean isJoinsResolved() {
      return joinsResolved;
    }

    // Includes both queried join paths and optional join paths
    public Set<String> getAllJoinPathColumnsOfTable(AbstractCubeTable table) {
      Set<String> allPaths = new HashSet<String>();
      for (Map<AbstractCubeTable, List<String>> optPaths : joinPathFromColumns.values()) {
        if (optPaths.get(table) != null) {
          allPaths.addAll(optPaths.get(table));
        }
      }

      for (Map<AbstractCubeTable, List<String>> optPaths : joinPathToColumns.values()) {
        if (optPaths.get(table) != null) {
          allPaths.addAll(optPaths.get(table));
        }
      }

      return allPaths;
    }

    public void pruneAllPaths(CubeInterface cube, final Set<CandidateFact> cfacts,
        final Map<Dimension, CandidateDim> dimsToQuery) {
      // Remove join paths which cannot be satisfied by the resolved candidate
      // fact and dimension tables
      if (cfacts != null) {
        // include columns from all picked facts
        Set<String> factColumns = new HashSet<String>();
        for (CandidateFact cfact : cfacts) {
          factColumns.addAll(cfact.getColumns());
        }

        for (List<SchemaGraph.JoinPath> paths : allPaths.values()) {
          for (int i = 0; i < paths.size(); i++) {
            SchemaGraph.JoinPath jp = paths.get(i);
            List<String> cubeCols = jp.getColumnsForTable((AbstractCubeTable) cube);
            if (cubeCols != null && !factColumns.containsAll(cubeCols)) {
              // This path requires some columns from the cube which are not
              // present in the candidate fact
              // Remove this path
              LOG.info("Removing join path:" + jp + " as columns :" + cubeCols + " dont exist");
              paths.remove(i);
              i--;
            }
          }
        }
        pruneEmptyPaths(allPaths);
      }
      pruneAllPaths(dimsToQuery);
    }

    /**
     * Prunes allPaths by removing paths which contain columns that are not present in any candidate dims.
     *
     * @param candidateDims
     */
    public void pruneAllPathsForCandidateDims(Map<Dimension, Set<CandidateDim>> candidateDims) {
      Map<Dimension, Set<String>> dimColumns = new HashMap<Dimension, Set<String>>();
      // populate all columns present in candidate dims for each dimension
      for (Map.Entry<Dimension, Set<CandidateDim>> entry : candidateDims.entrySet()) {
        Dimension dim = entry.getKey();
        Set<String> allColumns = new HashSet<String>();
        for (CandidateDim cdim : entry.getValue()) {
          allColumns.addAll(cdim.getColumns());
        }
        dimColumns.put(dim, allColumns);
      }
      for (List<SchemaGraph.JoinPath> paths : allPaths.values()) {
        for (int i = 0; i < paths.size(); i++) {
          SchemaGraph.JoinPath jp = paths.get(i);
          for (AbstractCubeTable refTable : jp.getAllTables()) {
            List<String> cols = jp.getColumnsForTable(refTable);
            if (refTable instanceof Dimension) {
              if (cols != null && !dimColumns.get(refTable).containsAll(cols)) {
                // This path requires some columns from the cube which are not present in any candidate dim
                // Remove this path
                LOG.info("Removing join path:" + jp + " as columns :" + cols + " dont exist");
                paths.remove(i);
                i--;
                break;
              }
            }
          }
        }
      }
      pruneEmptyPaths(allPaths);
    }

    private void pruneEmptyPaths(Map<Dimension, List<SchemaGraph.JoinPath>> allPaths) {
      Iterator<Map.Entry<Dimension, List<SchemaGraph.JoinPath>>> iter = allPaths.entrySet().iterator();
      while (iter.hasNext()) {
        Map.Entry<Dimension, List<SchemaGraph.JoinPath>> entry = iter.next();
        if (entry.getValue().isEmpty()) {
          iter.remove();
        }
      }
    }

    private Map<Dimension, List<SchemaGraph.JoinPath>> pruneFactPaths(CubeInterface cube, final CandidateFact cfact) {
      Map<Dimension, List<SchemaGraph.JoinPath>> prunedPaths = new HashMap<Dimension, List<SchemaGraph.JoinPath>>();
      // Remove join paths which cannot be satisfied by the candidate fact
      for (Map.Entry<Dimension, List<SchemaGraph.JoinPath>> ppaths : allPaths.entrySet()) {
        prunedPaths.put(ppaths.getKey(), new ArrayList<SchemaGraph.JoinPath>(ppaths.getValue()));
        List<SchemaGraph.JoinPath> paths = prunedPaths.get(ppaths.getKey());
        for (int i = 0; i < paths.size(); i++) {
          SchemaGraph.JoinPath jp = paths.get(i);
          List<String> cubeCols = jp.getColumnsForTable((AbstractCubeTable) cube);
          if (cubeCols != null && !cfact.getColumns().containsAll(cubeCols)) {
            // This path requires some columns from the cube which are not
            // present in the candidate fact
            // Remove this path
            LOG.info("Removing join path:" + jp + " as columns :" + cubeCols + " dont exist");
            paths.remove(i);
            i--;
          }
        }
      }
      pruneEmptyPaths(prunedPaths);
      return prunedPaths;
    }

    private void pruneAllPaths(final Map<Dimension, CandidateDim> dimsToQuery) {
      // Remove join paths which cannot be satisfied by the resolved dimension
      // tables
      if (dimsToQuery != null && !dimsToQuery.isEmpty()) {
        for (CandidateDim candidateDim : dimsToQuery.values()) {
          Set<String> dimCols = candidateDim.dimtable.getAllFieldNames();
          for (List<SchemaGraph.JoinPath> paths : allPaths.values()) {
            for (int i = 0; i < paths.size(); i++) {
              SchemaGraph.JoinPath jp = paths.get(i);
              List<String> candidateDimCols = jp.getColumnsForTable(candidateDim.getBaseTable());
              if (candidateDimCols != null && !dimCols.containsAll(candidateDimCols)) {
                // This path requires some columns from the dimension which are
                // not present in the candidate dim
                // Remove this path
                LOG.info("Removing join path:" + jp + " as columns :" + candidateDimCols + " dont exist");
                paths.remove(i);
                i--;
              }
            }
          }
        }
        pruneEmptyPaths(allPaths);
      }
    }

    /**
     * There can be multiple join paths between a dimension and the target. Set
     * of all possible join clauses is the cartesian product of join paths of
     * all dimensions
     */
    private Iterator<JoinClause> getJoinClausesForAllPaths(final CandidateFact fact,
        final Set<Dimension> qdims, final CubeQueryContext cubeql) {
      Map<Dimension, List<SchemaGraph.JoinPath>> allPaths = this.allPaths;
      // if fact is passed only look at paths possible from fact to dims
      if (fact != null) {
        allPaths = pruneFactPaths(cubeql.getCube(), fact);
      }
      // Number of paths in each path set
      final int groupSizes[] = new int[allPaths.values().size()];
      // Total number of elements in the cartesian product
      int numSamples = 1;
      // All path sets
      final List<List<SchemaGraph.JoinPath>> pathSets = new ArrayList<List<SchemaGraph.JoinPath>>();
      // Dimension corresponding to the path sets
      final Dimension dimensions[] = new Dimension[groupSizes.length];

      int i = 0;
      for (Map.Entry<Dimension, List<SchemaGraph.JoinPath>> entry : allPaths.entrySet()) {
        dimensions[i] = entry.getKey();
        List<SchemaGraph.JoinPath> group = entry.getValue();
        pathSets.add(group);
        groupSizes[i] = group.size();
        numSamples *= groupSizes[i];
        i++;
      }

      final int selection[] = new int[groupSizes.length];
      final int MAX_SAMPLE_COUNT = numSamples;

      // Return a lazy iterator over all possible join chains
      return new Iterator<JoinClause>() {
        int sample = 0;

        @Override
        public boolean hasNext() {
          return sample < MAX_SAMPLE_COUNT;
        }

        @Override
        public JoinClause next() {
          Map<Dimension, List<TableRelationship>> chain = new LinkedHashMap<Dimension, List<TableRelationship>>();
          //generate next permutation.
          for(int i = groupSizes.length - 1, base=sample; i >= 0; base /= groupSizes[i], i--) {
            selection[i] = base % groupSizes[i];
          }
          for (int i = 0; i < selection.length; i++) {
            int selectedPath = selection[i];
            List<TableRelationship> path = pathSets.get(i).get(selectedPath).getEdges();
            chain.put(dimensions[i], path);
          }

          Set<Dimension> dimsOnPath = getDimsOnPath(chain, qdims);

          sample++;
          // Cost of join = number of tables joined in the clause
          return new JoinClause(chain, dimsOnPath);
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("Cannot remove elements!");
        }
      };
    }

    public Set<Dimension> pickOptionalTables(final CandidateFact fact,
        Set<Dimension> qdims, CubeQueryContext cubeql) throws SemanticException {
      // Find the min cost join clause and add dimensions in the clause as optional dimensions 
      Set<Dimension> joiningOptionalTables = new HashSet<Dimension>();
      if (qdims == null) {
        return joiningOptionalTables;
      }
      // find least cost path
      Iterator<JoinClause> itr = getJoinClausesForAllPaths(fact, qdims, cubeql);
      JoinClause minCostClause = null;
      while (itr.hasNext()) {
        JoinClause clause = itr.next();
        if (minCostClause == null || minCostClause.getCost() > clause.getCost()) {
          minCostClause = clause;
        }
      }

      if (minCostClause == null) {
        throw new SemanticException(ErrorMsg.NO_JOIN_PATH, qdims.toString(), autoJoinTarget.getName());
      }

      LOG.info("Fact:" + fact + " minCostClause:" + minCostClause);
      if (fact != null) {
        cubeql.getAutoJoinCtx().getFactClauses().put(fact, minCostClause);
      } else {
        cubeql.getAutoJoinCtx().setMinCostClause(minCostClause);
      }
      for (Dimension dim : minCostClause.dimsInPath) {
        if (!qdims.contains(dim)) {
          joiningOptionalTables.add(dim);
        }
      }

      minCostClause.initChainColumns();
      // prune candidate dims of joiningOptionalTables wrt joinging columns
      for (Dimension dim : joiningOptionalTables) {
        for (Iterator<CandidateDim> i = cubeql.getCandidateDimTables().get(dim).iterator(); i.hasNext();) {
          CandidateDim cdim = i.next();
          CubeDimensionTable dimtable = cdim.dimtable;
          if (!cdim.getColumns().containsAll(minCostClause.chainColumns.get(dim))) {
            i.remove();
            LOG.info("Not considering dimtable:" + dimtable + " as its columns are"
                + " not part of any join paths. Join columns:" + minCostClause.chainColumns.get(dim));
            cubeql.addDimPruningMsgs(dim, cdim.dimtable, new CandidateTablePruneCause(dimtable.getName(),
                CubeTableCause.NO_COLUMN_PART_OF_A_JOIN_PATH));
            break;
          }
        }
        if (cubeql.getCandidateDimTables().get(dim).size() == 0) {
          throw new SemanticException(ErrorMsg.NO_DIM_HAS_COLUMN, dim.getName(),
              minCostClause.chainColumns.get(dim).toString());
        }
      }

      return joiningOptionalTables;
    }

    public Map<Dimension, List<SchemaGraph.JoinPath>> getAllPaths() {
      return allPaths;
    }

    public boolean isReachableDim(Dimension dim) {
      return allPaths.containsKey(dim) && !allPaths.get(dim).isEmpty();
    }

  }

  static String getJoinTypeStr(JoinType joinType) {
    if (joinType == null) {
      return "";
    }
    switch (joinType) {
    case FULLOUTER:
      return " full outer";
    case INNER:
      return " inner";
    case LEFTOUTER:
      return " left outer";
    case LEFTSEMI:
      return " left semi";
    case UNIQUE:
      return " unique";
    case RIGHTOUTER:
      return " right outer";
    default:
      return "";
    }
  }

  private Map<AbstractCubeTable, String> partialJoinConditions;
  private Map<AbstractCubeTable, JoinType> tableJoinTypeMap;
  private boolean partialJoinChain;
  private AbstractCubeTable target;
  private HiveConf conf;

  public JoinResolver(Configuration conf) {
  }

  @Override
  public void rewriteContext(CubeQueryContext cubeql) throws SemanticException {
    partialJoinConditions = new HashMap<AbstractCubeTable, String>();
    tableJoinTypeMap = new HashMap<AbstractCubeTable, JoinType>();
    try {
      conf = cubeql.getHiveConf();
      resolveJoins(cubeql);
    } catch (HiveException e) {
      throw new SemanticException(e);
    }
  }

  private void resolveJoins(CubeQueryContext cubeql) throws HiveException {
    QB cubeQB = cubeql.getQB();
    boolean joinResolverDisabled =
        conf.getBoolean(CubeQueryConfUtil.DISABLE_AUTO_JOINS, CubeQueryConfUtil.DEFAULT_DISABLE_AUTO_JOINS);
    if (joinResolverDisabled) {
      if (cubeql.getJoinTree() != null) {
        cubeQB.setQbJoinTree(genJoinTree(cubeQB, cubeql.getJoinTree(), cubeql));
      }
    } else {
      autoResolveJoins(cubeql);
    }
  }

  /**
   * Resolve joins automatically for the given query.
   * 
   * @param cubeql
   * @throws SemanticException
   */
  private void autoResolveJoins(CubeQueryContext cubeql) throws HiveException {
    // Check if this query needs a join -
    // A join is needed if there is a cube and at least one dimension, or, 0
    // cubes and more than one
    // dimensions
    Set<Dimension> dimensions = cubeql.getDimensions();
    // Add dimensions specified in the partial join tree
    ASTNode joinClause = cubeql.getQB().getParseInfo().getJoinExpr();
    if (joinClause == null) {
      // Only cube in the query
      if (cubeql.hasCubeInQuery()) {
        target = (AbstractCubeTable) cubeql.getCube();
      } else {
        String targetDimAlias = cubeql.getQB().getTabAliases().iterator().next();
        String targetDimTable = cubeql.getQB().getTabNameForAlias(targetDimAlias);
        if (targetDimTable == null) {
          LOG.warn("Null table for alias " + targetDimAlias);
        }
        target = cubeql.getMetastoreClient().getDimension(targetDimTable);
      }
    }
    searchDimensionTables(cubeql.getMetastoreClient(), joinClause);
    if (target == null) {
      LOG.warn("Can't resolve joins for null target");
      return;
    }

    Set<Dimension> dimTables = new HashSet<Dimension>(dimensions);
    for (AbstractCubeTable partiallyJoinedTable : partialJoinConditions.keySet()) {
      dimTables.add((Dimension) partiallyJoinedTable);
    }
    Set<String> joinChainDims = new HashSet<String>();
    // Add dimensions from joinchains
    for (JoinChain chain : cubeql.getJoinchains().values()) {
      for (String dimName : chain.getIntermediateDimensions()) {
        joinChainDims.add(dimName);
        cubeql.addOptionalDimTable(dimName, null, null, true);
      }
    }
    // add optional dimensions
    dimTables.addAll(cubeql.getOptionalDimensions());

    // Remove target
    dimTables.remove(target);
    if (dimTables.isEmpty() && !joinChainDims.contains(target)) {
      // Joins not required
      LOG.info("No dimension tables to resolve!");
      return;
    }

    SchemaGraph graph = cubeql.getMetastoreClient().getSchemaGraph();
    Map<Dimension, List<SchemaGraph.JoinPath>> multipleJoinPaths =
        new LinkedHashMap<Dimension, List<SchemaGraph.JoinPath>>();

    // Resolve join path for each dimension accessed in the query
    for (Dimension joinee : dimTables) {
      if (joinChainDims.contains(joinee.getName()) || isJoinchainDestination(cubeql, joinee.getName())) {
        // this dimension is part of a path of the join chain
        // we should skip adding all paths
        LOG.info("Not adding allpaths to target for " + joinee);
        continue;
      }
      // Find all possible join paths
      SchemaGraph.GraphSearch search = new SchemaGraph.GraphSearch(joinee, target, graph);
      List<SchemaGraph.JoinPath> joinPaths = search.findAllPathsToTarget();
      if (joinPaths != null && !joinPaths.isEmpty()) {
        multipleJoinPaths.put(joinee, new ArrayList<SchemaGraph.JoinPath>(search.findAllPathsToTarget()));
        addOptionalTables(cubeql, multipleJoinPaths.get(joinee), cubeql.getDimensions().contains(joinee));
      } else {
        // No link to cube from this dim, can't proceed with query
        if (LOG.isDebugEnabled()) {
          graph.print();
        }
        LOG.warn("No join path between " + joinee.getName() + " and " + target.getName());
        if (cubeql.getDimensions().contains(joinee)) {
          throw new SemanticException(ErrorMsg.NO_JOIN_PATH, joinee.getName(), target.getName());
        } else {
          // if joinee is optional dim table, remove those candidate facts
          Set<CandidateTable> candidates = cubeql.getOptionalDimensionMap().get(joinee).requiredForCandidates;
          for (CandidateTable candidate : candidates) {
            if (candidate instanceof CandidateFact) {
              if (cubeql.getCandidateFactTables().contains(candidate)) {
                LOG.info("Not considering fact:" + candidate + " as there is no join path to " + joinee);
                cubeql.getCandidateFactTables().remove(candidate);
                cubeql.addFactPruningMsgs(((CandidateFact) candidate).fact, new CandidateTablePruneCause(
                    ((CandidateFact) candidate).fact.getName(), CubeTableCause.COLUMN_NOT_FOUND));
              }
            } else if (cubeql.getCandidateDimTables().containsKey(((CandidateDim) candidate).getBaseTable())) {
              LOG.info("Not considering dimtable:" + candidate + " as there is no join path to " + joinee);
              cubeql.getCandidateDimTables().get(((CandidateDim) candidate).getBaseTable()).remove(candidate);
              cubeql.addDimPruningMsgs((Dimension) candidate.getBaseTable(), (CubeDimensionTable) candidate.getTable(),
                  new CandidateTablePruneCause(candidate.getName(), CubeTableCause.COLUMN_NOT_FOUND));
            }
          }
        }
      }
    }

    // populate paths from joinchains
    for (JoinChain chain : cubeql.getJoinchains().values()) {
      multipleJoinPaths.put(cubeql.getMetastoreClient().getDimension(chain.getDestTable()),
          chain.getRelationEdges(cubeql.getMetastoreClient()));
    }
    AutoJoinContext joinCtx =
        new AutoJoinContext(multipleJoinPaths, cubeql.optionalDimensions, partialJoinConditions, partialJoinChain,
            tableJoinTypeMap, target, conf.get(CubeQueryConfUtil.JOIN_TYPE_KEY), true);
    cubeql.setAutoJoinCtx(joinCtx);
  }

  private boolean isJoinchainDestination(CubeQueryContext cubeql, String dimName) {
    for (JoinChain chain : cubeql.getJoinchains().values()) {
      if (chain.getDestTable().equalsIgnoreCase(dimName)) {
        return true;
      }
    }
    return false;
  }
  private void addOptionalTables(CubeQueryContext cubeql, List<SchemaGraph.JoinPath> joinPathList, boolean required)
      throws SemanticException {
    for (SchemaGraph.JoinPath joinPath : joinPathList) {
      for (TableRelationship rel : joinPath.getEdges()) {
        // Add the joined tables to the queries table sets so that they are
        // resolved in candidate resolver
        cubeql.addOptionalDimTable(rel.getToTable().getName(), null, null, required);
      }
    }
  }

  private void setTarget(CubeMetastoreClient metastore, ASTNode node) throws HiveException {
    String targetTableName = HQLParser.getString(HQLParser.findNodeByPath(node, TOK_TABNAME, Identifier));
    if (metastore.isDimension(targetTableName)) {
      target = metastore.getDimension(targetTableName);
    } else if (metastore.isCube(targetTableName)) {
      target = (AbstractCubeTable) metastore.getCube(targetTableName);
    } else {
      throw new SemanticException(ErrorMsg.JOIN_TARGET_NOT_CUBE_TABLE, targetTableName);
    }
  }

  private void searchDimensionTables(CubeMetastoreClient metastore, ASTNode node) throws HiveException {
    if (node == null) {
      return;
    }
    // User has specified join conditions partially. We need to store join
    // conditions as well as join types
    partialJoinChain = true;
    if (isJoinToken(node)) {
      ASTNode left = (ASTNode) node.getChild(0);
      ASTNode right = (ASTNode) node.getChild(1);
      // Get table name and

      String tableName = HQLParser.getString(HQLParser.findNodeByPath(right, TOK_TABNAME, Identifier));

      Dimension dimension = metastore.getDimension(tableName);
      String joinCond = "";
      if (node.getChildCount() > 2) {
        // User has specified a join condition for filter pushdown.
        joinCond = HQLParser.getString((ASTNode) node.getChild(2));
      }
      partialJoinConditions.put(dimension, joinCond);
      tableJoinTypeMap.put(dimension, getJoinType(node));
      if (isJoinToken(left)) {
        searchDimensionTables(metastore, left);
      } else {
        if (left.getToken().getType() == TOK_TABREF) {
          setTarget(metastore, left);
        }
      }
    } else if (node.getToken().getType() == TOK_TABREF) {
      setTarget(metastore, node);
    }

  }

  private JoinType getJoinType(ASTNode node) {
    switch (node.getToken().getType()) {
    case TOK_LEFTOUTERJOIN:
      return JoinType.LEFTOUTER;
    case TOK_LEFTSEMIJOIN:
      return JoinType.LEFTSEMI;
    case TOK_RIGHTOUTERJOIN:
      return JoinType.RIGHTOUTER;
    case TOK_FULLOUTERJOIN:
      return JoinType.FULLOUTER;
    case TOK_JOIN:
      return JoinType.INNER;
    case TOK_UNIQUEJOIN:
      return JoinType.UNIQUE;
    default:
      return JoinType.INNER;
    }
  }

  // Recursively find out join conditions
  private QBJoinTree genJoinTree(QB qb, ASTNode joinParseTree, CubeQueryContext cubeql) throws SemanticException {
    QBJoinTree joinTree = new QBJoinTree();
    JoinCond[] condn = new JoinCond[1];

    // Figure out join condition descriptor
    switch (joinParseTree.getToken().getType()) {
    case TOK_LEFTOUTERJOIN:
      joinTree.setNoOuterJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.LEFTOUTER);
      break;
    case TOK_RIGHTOUTERJOIN:
      joinTree.setNoOuterJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.RIGHTOUTER);
      break;
    case TOK_FULLOUTERJOIN:
      joinTree.setNoOuterJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.FULLOUTER);
      break;
    case TOK_LEFTSEMIJOIN:
      joinTree.setNoSemiJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.LEFTSEMI);
      break;
    default:
      condn[0] = new JoinCond(0, 1, JoinType.INNER);
      joinTree.setNoOuterJoin(true);
      break;
    }

    joinTree.setJoinCond(condn);

    ASTNode left = (ASTNode) joinParseTree.getChild(0);
    ASTNode right = (ASTNode) joinParseTree.getChild(1);

    // Left subtree is table or a subquery
    if ((left.getToken().getType() == TOK_TABREF) || (left.getToken().getType() == TOK_SUBQUERY)) {
      String tableName = SemanticAnalyzer.getUnescapedUnqualifiedTableName((ASTNode) left.getChild(0)).toLowerCase();
      String alias =
          left.getChildCount() == 1 ? tableName : SemanticAnalyzer.unescapeIdentifier(left
              .getChild(left.getChildCount() - 1).getText().toLowerCase());

      joinTree.setLeftAlias(alias);

      String[] leftAliases = new String[1];
      leftAliases[0] = alias;
      joinTree.setLeftAliases(leftAliases);

      String[] children = new String[2];
      children[0] = alias;
      joinTree.setBaseSrc(children);

    } else if (isJoinToken(left)) {
      // Left subtree is join token itself, so recurse down
      QBJoinTree leftTree = genJoinTree(qb, left, cubeql);

      joinTree.setJoinSrc(leftTree);

      String[] leftChildAliases = leftTree.getLeftAliases();
      String leftAliases[] = new String[leftChildAliases.length + 1];
      for (int i = 0; i < leftChildAliases.length; i++) {
        leftAliases[i] = leftChildAliases[i];
      }
      leftAliases[leftChildAliases.length] = leftTree.getRightAliases()[0];
      joinTree.setLeftAliases(leftAliases);

    } else {
      assert (false);
    }

    if ((right.getToken().getType() == TOK_TABREF) || (right.getToken().getType() == TOK_SUBQUERY)) {
      String tableName = SemanticAnalyzer.getUnescapedUnqualifiedTableName((ASTNode) right.getChild(0)).toLowerCase();
      String alias =
          right.getChildCount() == 1 ? tableName : SemanticAnalyzer.unescapeIdentifier(right
              .getChild(right.getChildCount() - 1).getText().toLowerCase());
      String[] rightAliases = new String[1];
      rightAliases[0] = alias;
      joinTree.setRightAliases(rightAliases);
      String[] children = joinTree.getBaseSrc();
      if (children == null) {
        children = new String[2];
      }
      children[1] = alias;
      joinTree.setBaseSrc(children);
      // remember rhs table for semijoin
      if (joinTree.getNoSemiJoin() == false) {
        joinTree.addRHSSemijoin(alias);
      }
    } else {
      assert false;
    }

    ASTNode joinCond = (ASTNode) joinParseTree.getChild(2);
    if (joinCond != null) {
      cubeql.setJoinCond(joinTree, HQLParser.getString(joinCond));
    } else {
      // No join condition specified. this should be an error
      new SemanticException(ErrorMsg.NO_JOIN_CONDITION_AVAIABLE);
    }
    return joinTree;
  }

  private boolean isJoinToken(ASTNode node) {
    if ((node.getToken().getType() == TOK_JOIN) || (node.getToken().getType() == TOK_LEFTOUTERJOIN)
        || (node.getToken().getType() == TOK_RIGHTOUTERJOIN) || (node.getToken().getType() == TOK_FULLOUTERJOIN)
        || (node.getToken().getType() == TOK_LEFTSEMIJOIN) || (node.getToken().getType() == TOK_UNIQUEJOIN)) {
      return true;
    }
    return false;
  }
}
