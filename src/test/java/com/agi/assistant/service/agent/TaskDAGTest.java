package com.agi.assistant.service.agent;

import com.agi.assistant.model.enums.NodeType;
import com.agi.assistant.model.enums.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TaskDAG} 图结构测试。
 * <p>
 * DAG 是编排的地基：拓扑序错了会导致下游读到 null，环没检出会导致调度器死锁。
 */
class TaskDAGTest {

    private static TaskDAG.TaskNode node(String id) {
        return TaskDAG.TaskNode.builder().id(id).type(NodeType.MERGE).build();
    }

    @Test
    @DisplayName("重复节点 / 空节点被拒绝")
    void invalidNodesRejected() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));

        assertThatThrownBy(() -> dag.addNode(node("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        assertThatThrownBy(() -> dag.addNode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("边指向不存在的节点被拒绝")
    void edgesToUnknownNodesRejected() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));

        assertThatThrownBy(() -> dag.addEdge("a", "ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target node not found");
        assertThatThrownBy(() -> dag.addEdge("ghost", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source node not found");
    }

    @Test
    @DisplayName("自环被拒绝")
    void selfLoopRejected() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));

        assertThatThrownBy(() -> dag.addEdge("a", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Self-loop");
    }

    @Test
    @DisplayName("成环的边被拒绝并回滚（不留半个脏边）")
    void cycleIsRejectedAndRolledBack() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));
        dag.addNode(node("b"));
        dag.addEdge("a", "b");

        assertThatThrownBy(() -> dag.addEdge("b", "a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");

        // 回滚后 b 依旧只依赖 a，a 不依赖任何节点
        assertThat(dag.getDependencies("b")).hasSize(1);
        assertThat(dag.getDependencies("a")).isEmpty();
        assertThat(dag.getDependents("b")).isEmpty();
        assertThat(dag.topologicalSort()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("拓扑序保证依赖先于被依赖者")
    void topologicalSortRespectsDependencies() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("c"));
        dag.addNode(node("a"));
        dag.addNode(node("b"));
        dag.addEdge("a", "b");
        dag.addEdge("b", "c");

        List<String> order = dag.topologicalSort();

        assertThat(order).hasSize(3);
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
        assertThat(order.indexOf("b")).isLessThan(order.indexOf("c"));
    }

    @Test
    @DisplayName("根节点 / 前驱 / 后继查询正确")
    void rootsAndAdjacencyQueries() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));
        dag.addNode(node("b"));
        dag.addNode(node("c"));
        dag.addEdge("a", "c");
        dag.addEdge("b", "c");

        assertThat(dag.getRoots()).extracting(TaskDAG.TaskNode::getId)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(dag.getDependencies("c")).extracting(TaskDAG.TaskNode::getId)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(dag.getDependents("a")).extracting(TaskDAG.TaskNode::getId)
                .containsExactly("c");
        assertThat(dag.getDependencies("a")).isEmpty();
        assertThat(dag.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("节点状态与结果可更新，未知节点安全返回")
    void nodeStateUpdates() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));

        dag.updateNodeStatus("a", TaskStatus.RUNNING);
        assertThat(dag.getNode("a").getStatus()).isEqualTo(TaskStatus.RUNNING);

        dag.setNodeResult("a", "value");
        assertThat(dag.getNode("a").getResult()).isEqualTo("value");

        // 未知节点：不抛异常，返回 null
        assertThat(dag.getNode("ghost")).isNull();
        dag.updateNodeStatus("ghost", TaskStatus.FAILED);
        dag.setNodeResult("ghost", "x");
    }

    @Test
    @DisplayName("clear 清空所有节点与边")
    void clearResetsGraph() {
        TaskDAG dag = new TaskDAG();
        dag.addNode(node("a"));
        dag.addNode(node("b"));
        dag.addEdge("a", "b");

        dag.clear();

        assertThat(dag.size()).isZero();
        assertThat(dag.getAllNodes()).isEmpty();
        assertThat(dag.getRoots()).isEmpty();
        assertThat(dag.topologicalSort()).isEmpty();
    }
}
