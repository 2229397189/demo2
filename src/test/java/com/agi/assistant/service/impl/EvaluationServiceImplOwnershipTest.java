package com.agi.assistant.service.impl;

import com.agi.assistant.mapper.EvaluationResultMapper;
import com.agi.assistant.mapper.EvaluationTaskMapper;
import com.agi.assistant.model.entity.EvaluationTask;
import com.agi.assistant.model.enums.EvaluationStatus;
import com.agi.assistant.service.evaluation.EvaluationRunner;
import com.agi.assistant.service.security.AccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvaluationServiceImpl} 的离线单测，覆盖两条缺陷：
 * <ol>
 *   <li><b>越权校验</b>：{@code getTaskResults} / {@code compareResults} 必须校验任务归属，
 *       非归属用户访问 → 拒绝（{@link AccessDeniedException}），不返回别人的数据；</li>
 *   <li><b>永久卡 RUNNING</b>：异步提交被线程池拒绝时，任务必须置为 FAILED 而不是停在 RUNNING。</li>
 * </ol>
 *
 * @author Alex
 */
class EvaluationServiceImplOwnershipTest {

    private EvaluationTaskMapper evaluationTaskMapper;
    private EvaluationResultMapper evaluationResultMapper;
    private EvaluationRunner evaluationRunner;
    private EvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        evaluationTaskMapper = mock(EvaluationTaskMapper.class);
        evaluationResultMapper = mock(EvaluationResultMapper.class);
        evaluationRunner = mock(EvaluationRunner.class);
        service = new EvaluationServiceImpl(
                evaluationTaskMapper, evaluationResultMapper, evaluationRunner, new ObjectMapper());
    }

    // ----------------------------------------------------------------
    //  缺陷 3：越权校验
    // ----------------------------------------------------------------

    @Test
    @DisplayName("getTaskResults：非归属用户访问被拒（403），归属用户正常返回")
    void getTaskResultsEnforcesOwnership() {
        when(evaluationTaskMapper.selectById(5L)).thenReturn(task(5L, 1L));
        when(evaluationResultMapper.selectList(any())).thenReturn(List.of());

        // 非归属用户 2 访问用户 1 的任务 → 拒绝
        assertThatThrownBy(() -> service.getTaskResults(5L, 2L))
                .isInstanceOf(AccessDeniedException.class);

        // 归属用户 1 → 正常
        assertThat(service.getTaskResults(5L, 1L)).isEmpty();
    }

    @Test
    @DisplayName("compareResults：任一任务不属于当前用户即拒绝")
    void compareResultsEnforcesOwnership() {
        when(evaluationTaskMapper.selectById(5L)).thenReturn(task(5L, 1L));
        when(evaluationTaskMapper.selectById(6L)).thenReturn(task(6L, 1L));
        when(evaluationResultMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.compareResults(5L, 6L, 2L))
                .isInstanceOf(AccessDeniedException.class);

        // 归属用户 → 正常返回对比结构
        assertThat(service.compareResults(5L, 6L, 1L)).containsKeys("taskA", "taskB", "metricsComparison");
    }

    // ----------------------------------------------------------------
    //  缺陷 4：异步提交被拒绝 → FAILED（不是 RUNNING）
    // ----------------------------------------------------------------

    @Test
    @DisplayName("异步提交被线程池拒绝 → 任务终态为 FAILED，不残留 RUNNING")
    void rejectedAsyncSubmissionMarksTaskFailed() {
        when(evaluationTaskMapper.selectById(9L)).thenReturn(task(9L, 1L));
        doThrow(new RejectedExecutionException("queue full"))
                .when(evaluationRunner).runEvaluation(9L);

        assertThatThrownBy(() -> service.runTask(9L))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<EvaluationTask> captor = ArgumentCaptor.forClass(EvaluationTask.class);
        verify(evaluationTaskMapper, atLeast(2)).updateById(captor.capture());
        EvaluationTask last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getStatus())
                .as("被拒绝的任务终态必须是 FAILED")
                .isEqualTo(EvaluationStatus.FAILED.getCode());
        assertThat(last.getStatus()).isNotEqualTo(EvaluationStatus.RUNNING.getCode());
    }

    private static EvaluationTask task(long id, long userId) {
        EvaluationTask task = new EvaluationTask();
        task.setId(id);
        task.setUserId(userId);
        task.setStatus(EvaluationStatus.PENDING.getCode());
        return task;
    }
}
