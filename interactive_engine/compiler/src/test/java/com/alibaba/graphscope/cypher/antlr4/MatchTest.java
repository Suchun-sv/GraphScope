/*
 * Copyright 2020 Alibaba Group Holding Limited.
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

package com.alibaba.graphscope.cypher.antlr4;

import com.alibaba.graphscope.common.ir.planner.rules.NotMatchToAntiJoinRule;
import com.alibaba.graphscope.common.ir.rel.graph.GraphLogicalSource;
import com.alibaba.graphscope.common.ir.tools.LogicalPlan;

import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Assert;
import org.junit.Test;

public class MatchTest {
    @Test
    public void match_1_test() {
        RelNode source = Utils.eval("Match (n) Return n").build();
        Assert.assertEquals(
                "GraphLogicalProject(n=[n], isAppend=[false])\n"
                    + "  GraphLogicalSource(tableConfig=[{isAll=true, tables=[software, person]}],"
                    + " alias=[n], opt=[VERTEX])",
                source.explain().trim());
    }

    @Test
    public void match_2_test() {
        RelNode source =
                Utils.eval("Match (n:person)-[x:knows]->(y:person) Return n, x, y").build();
        Assert.assertEquals(
                "GraphLogicalProject(n=[n], x=[x], y=[y], isAppend=[false])\n"
                    + "  GraphLogicalSingleMatch(input=[null],"
                    + " sentence=[GraphLogicalGetV(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[y], opt=[END])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=false, tables=[knows]}], alias=[x],"
                    + " opt=[OUT])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[n], opt=[VERTEX])\n"
                    + "], matchOpt=[INNER])",
                source.explain().trim());
    }

    @Test
    public void match_3_test() {
        RelNode match = Utils.eval("Match (a)-[]->(b), (b)-[]->(c) Return a, b, c").build();
        Assert.assertEquals(
                "GraphLogicalProject(a=[a], b=[b], c=[c], isAppend=[false])\n"
                    + "  GraphLogicalMultiMatch(input=[null],"
                    + " sentences=[{s0=[GraphLogicalGetV(tableConfig=[{isAll=true,"
                    + " tables=[software, person]}], alias=[b], opt=[END])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[DEFAULT], opt=[OUT])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[a], opt=[VERTEX])\n"
                    + "], s1=[GraphLogicalGetV(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[c], opt=[END])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[DEFAULT], opt=[OUT])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[b], opt=[VERTEX])\n"
                    + "]}])",
                match.explain().trim());
    }

    // for the sentence `(a:person)-[b:knows*1..3]-(c:person)`:
    // b is a path_expand operator, expand base should be `knows` type, getV base should be any
    // vertex types adjacent to knows (currently we have not implemented type inference based on
    // graph schema, so all vertex types are considered here)
    // c is a getV operator which should be `person` type
    @Test
    public void match_4_test() {
        RelNode match =
                Utils.eval(
                                "Match (a:person)-[b:knows*1..3 {weight:1.0}]->(c:person {name:"
                                        + " 'marko'}) Return a, b")
                        .build();
        Assert.assertEquals(
                "GraphLogicalProject(a=[a], b=[b], isAppend=[false])\n"
                    + "  GraphLogicalSingleMatch(input=[null],"
                    + " sentence=[GraphLogicalGetV(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[c], fusedFilter=[[=(DEFAULT.name, _UTF-8'marko')]], opt=[END])\n"
                    + "  GraphLogicalPathExpand(expand=[GraphLogicalExpand(tableConfig=[{isAll=false,"
                    + " tables=[knows]}], alias=[DEFAULT], fusedFilter=[[=(DEFAULT.weight,"
                    + " 1.0E0)]], opt=[OUT])\n"
                    + "], getV=[GraphLogicalGetV(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[DEFAULT], opt=[END])\n"
                    + "], offset=[1], fetch=[2], path_opt=[ARBITRARY], result_opt=[END_V],"
                    + " alias=[b])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[a], opt=[VERTEX])\n"
                    + "], matchOpt=[INNER])",
                match.explain().trim());
    }

    @Test
    public void match_5_test() {
        RelNode match = Utils.eval("Match (n:person {age: $age}) Return n").build();
        Assert.assertEquals(
                "GraphLogicalProject(n=[n], isAppend=[false])\n"
                        + "  GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                        + " alias=[n], fusedFilter=[[=(DEFAULT.age, ?0)]], opt=[VERTEX])",
                match.explain().trim());
    }

    @Test
    public void match_6_test() {
        RelNode project = Utils.eval("Match (a:person {id: 2l}) Return a").build();
        GraphLogicalSource source = (GraphLogicalSource) project.getInput(0);
        RexCall condition = (RexCall) source.getFilters().get(0);
        Assert.assertEquals(
                SqlTypeName.BIGINT, condition.getOperands().get(1).getType().getSqlTypeName());
    }

    // Match (a:person)-[x:knows]->(b:person), (b:person)-[:knows]-(c:person)
    // Optional Match (a:person)-[]->(c:person)
    // Return a
    @Test
    public void match_7_test() {
        RelNode multiMatch =
                Utils.eval(
                                "Match (a:person)-[x:knows]->(b:person),"
                                        + " (b:person)-[:knows]-(c:person) Optional Match"
                                        + " (a:person)-[]->(c:person) Return a")
                        .build();
        Assert.assertEquals(
                "GraphLogicalProject(a=[a], isAppend=[false])\n"
                    + "  LogicalJoin(condition=[AND(=(a, a), =(c, c))], joinType=[left])\n"
                    + "    GraphLogicalMultiMatch(input=[null],"
                    + " sentences=[{s0=[GraphLogicalGetV(tableConfig=[{isAll=false,"
                    + " tables=[person]}], alias=[b], opt=[END])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=false, tables=[knows]}], alias=[x],"
                    + " opt=[OUT])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[a], opt=[VERTEX])\n"
                    + "], s1=[GraphLogicalGetV(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[c], opt=[OTHER])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=false, tables=[knows]}],"
                    + " alias=[DEFAULT], opt=[BOTH])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[b], opt=[VERTEX])\n"
                    + "]}])\n"
                    + "    GraphLogicalSingleMatch(input=[null],"
                    + " sentence=[GraphLogicalGetV(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[c], opt=[END])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[DEFAULT], opt=[OUT])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[a], opt=[VERTEX])\n"
                    + "], matchOpt=[INNER])",
                multiMatch.explain().trim());
    }

    @Test
    public void match_8_test() {
        RelNode multiMatch = Utils.eval("Match (a) Where not (a)-[c]-(b) Return a Limit 1").build();
        Assert.assertEquals(
                "GraphLogicalSort(fetch=[1])\n"
                    + "  GraphLogicalProject(a=[a], isAppend=[false])\n"
                    + "    LogicalFilter(condition=[NOT(EXISTS({\n"
                    + "GraphLogicalGetV(tableConfig=[{isAll=true, tables=[software, person]}],"
                    + " alias=[b], opt=[OTHER])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[c], opt=[BOTH])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[a], opt=[VERTEX])\n"
                    + "}))])\n"
                    + "      GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[a], opt=[VERTEX])",
                multiMatch.explain().trim());
        RelOptPlanner planner =
                com.alibaba.graphscope.common.ir.Utils.mockPlanner(
                        NotMatchToAntiJoinRule.Config.DEFAULT);
        planner.setRoot(multiMatch);
        RelNode after = planner.findBestExp();
        Assert.assertEquals(
                "GraphLogicalSort(fetch=[1])\n"
                    + "  GraphLogicalProject(a=[a], isAppend=[false])\n"
                    + "    LogicalJoin(condition=[=(a, a)], joinType=[anti])\n"
                    + "      GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[a], opt=[VERTEX])\n"
                    + "      GraphLogicalSingleMatch(input=[null],"
                    + " sentence=[GraphLogicalGetV(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[b], opt=[OTHER])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[c], opt=[BOTH])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[a], opt=[VERTEX])\n"
                    + "], matchOpt=[INNER])",
                after.explain().trim());
    }

    @Test
    public void match_9_test() {
        LogicalPlan plan =
                Utils.evalLogicalPlan(
                        "Match (n:person {name: $name}) Where n.age = $age Return n.id;");
        Assert.assertEquals(
                "[Parameter{name='name', dataType=CHAR(1)}, Parameter{name='age',"
                        + " dataType=INTEGER}]",
                plan.getDynamicParams().toString());
        Assert.assertEquals("RecordType(BIGINT id)", plan.getOutputType().toString());
    }

    // add a new test case for match without any dynamic params
    @Test
    public void match_10_test() {
        LogicalPlan plan = Utils.evalLogicalPlan("Match (n:person) Return n.id;");
        Assert.assertTrue(plan.getDynamicParams().isEmpty());
        Assert.assertEquals("RecordType(BIGINT id)", plan.getOutputType().toString());
    }

    // add a new test case for match with multiple dynamic params
    @Test
    public void match_11_test() {
        LogicalPlan plan =
                Utils.evalLogicalPlan(
                        "Match (n:person {name: $name, age: $age}) Where n.id > 10 Return n.id,"
                                + " n.name;");
        Assert.assertEquals(
                "[Parameter{name='name', dataType=CHAR(1)}, Parameter{name='age',"
                        + " dataType=INTEGER}]",
                plan.getDynamicParams().toString());
        Assert.assertEquals("RecordType(BIGINT id, CHAR(1) name)", plan.getOutputType().toString());
    }

    @Test
    public void match_12_test() {
        try {
            RelNode node = Utils.eval("Match (a:人类) Return a").build();
        } catch (CalciteException e) {
            Assert.assertEquals("Table '人类' not found", e.getMessage());
            return;
        }
        Assert.fail();
    }

    @Test
    public void match_13_test() {
        try {
            RelNode node = Utils.eval("Match (a:person {名称:'marko'}) Return a").build();
        } catch (IllegalArgumentException e) {
            Assert.assertEquals(
                    "{property=名称} not found; expected properties are: [id, name, age]",
                    e.getMessage());
            return;
        }
        Assert.fail();
    }

    @Test
    public void match_14_test() {
        RelNode node = Utils.eval("Match (a:person {name:'小明'}) Return '小明'").build();
        Assert.assertEquals(
                "GraphLogicalProject($f0=[_UTF-8'小明'], isAppend=[false])\n"
                        + "  GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                        + " alias=[a], fusedFilter=[[=(DEFAULT.name, _UTF-8'小明')]], opt=[VERTEX])",
                node.explain().trim());
        Assert.assertEquals(
                SqlTypeName.CHAR,
                node.getRowType().getFieldList().get(0).getType().getSqlTypeName());
    }

    @Test
    public void match_15_test() {
        RelNode node = Utils.eval("Match (a)-[b]-(c) Return labels(a), type(b)").build();
        Assert.assertEquals(
                "GraphLogicalProject(~label=[a.~label], ~label0=[b.~label], isAppend=[false])\n"
                    + "  GraphLogicalSingleMatch(input=[null],"
                    + " sentence=[GraphLogicalGetV(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[c], opt=[OTHER])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[b], opt=[BOTH])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=true, tables=[software,"
                    + " person]}], alias=[a], opt=[VERTEX])\n"
                    + "], matchOpt=[INNER])",
                node.explain().trim());
    }

    @Test
    public void match_16_test() {
        RelNode node =
                Utils.eval(
                                "Match (a:person {name: $name})-[b]->(c:person {name: $name})"
                                        + " Return a, c")
                        .build();
        Assert.assertEquals(
                "GraphLogicalProject(a=[a], c=[c], isAppend=[false])\n"
                    + "  GraphLogicalSingleMatch(input=[null],"
                    + " sentence=[GraphLogicalGetV(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[c], fusedFilter=[[=(DEFAULT.name, ?0)]], opt=[END])\n"
                    + "  GraphLogicalExpand(tableConfig=[{isAll=true, tables=[created, knows]}],"
                    + " alias=[b], opt=[OUT])\n"
                    + "    GraphLogicalSource(tableConfig=[{isAll=false, tables=[person]}],"
                    + " alias=[a], fusedFilter=[[=(DEFAULT.name, ?0)]], opt=[VERTEX])\n"
                    + "], matchOpt=[INNER])",
                node.explain().trim());
    }
}
