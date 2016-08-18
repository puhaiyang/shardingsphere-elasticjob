/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package com.dangdang.ddframe.job.cloud.agent.executor;

import com.dangdang.ddframe.job.api.ElasticJob;
import com.dangdang.ddframe.job.api.JobExecutorFactory;
import com.dangdang.ddframe.job.api.ShardingContext;
import com.dangdang.ddframe.job.api.exception.JobSystemException;
import com.dangdang.ddframe.job.api.type.script.api.ScriptJob;
import com.dangdang.ddframe.job.cloud.agent.internal.CloudJobFacade;
import com.dangdang.ddframe.job.cloud.agent.internal.JobConfigurationContext;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.mesos.Executor;
import org.apache.mesos.ExecutorDriver;
import org.apache.mesos.Protos;

import java.util.Map;

/**
 * 常驻作业任务执行器.
 *
 * @author zhangliang
 */
public final class TaskExecutor implements Executor {
    
    @Override
    public void registered(final ExecutorDriver executorDriver, final Protos.ExecutorInfo executorInfo, final Protos.FrameworkInfo frameworkInfo, final Protos.SlaveInfo slaveInfo) {
    }
    
    @Override
    public void reregistered(final ExecutorDriver executorDriver, final Protos.SlaveInfo slaveInfo) {
    }
    
    @Override
    public void disconnected(final ExecutorDriver executorDriver) {
    }
    
    @Override
    public void launchTask(final ExecutorDriver executorDriver, final Protos.TaskInfo taskInfo) {
        executorDriver.sendStatusUpdate(Protos.TaskStatus.newBuilder().setTaskId(taskInfo.getTaskId()).setState(Protos.TaskState.TASK_RUNNING).build());
        Map<String, Object> data = SerializationUtils.deserialize(taskInfo.getData().toByteArray());
        ShardingContext shardingContext = (ShardingContext) data.get("shardingContext");
        @SuppressWarnings("unchecked")
        JobConfigurationContext jobConfig = new JobConfigurationContext((Map<String, String>) data.get("jobConfigContext"));
        try {
            ElasticJob elasticJob = getElasticJobInstance(jobConfig);
            if (jobConfig.isTransient()) {
                JobExecutorFactory.getJobExecutor(elasticJob, new CloudJobFacade(shardingContext, jobConfig)).execute();
                executorDriver.sendStatusUpdate(Protos.TaskStatus.newBuilder().setTaskId(taskInfo.getTaskId()).setState(Protos.TaskState.TASK_FINISHED).build());
            } else {
                new DaemonTaskScheduler(elasticJob, jobConfig, new CloudJobFacade(shardingContext, jobConfig), executorDriver, taskInfo.getTaskId()).init();
            }
            // CHECKSTYLE:OFF
        } catch (final Throwable ex) {
            // CHECKSTYLE:ON
            executorDriver.sendStatusUpdate(Protos.TaskStatus.newBuilder().setTaskId(taskInfo.getTaskId()).setState(Protos.TaskState.TASK_ERROR).build());
            throw ex;
        }
    }
    
    private ElasticJob getElasticJobInstance(final JobConfigurationContext jobConfig) {
        String jobClass = jobConfig.getTypeConfig().getJobClass();
        try {
            Class<?> elasticJobClass = Class.forName(jobClass);
            if (!ElasticJob.class.isAssignableFrom(elasticJobClass)) {
                throw new JobSystemException("Elastic-Job: Class '%s' must implements ElasticJob interface.", jobClass);
            }
            if (elasticJobClass != ScriptJob.class) {
                return (ElasticJob) elasticJobClass.newInstance();
            }
            return null;
        } catch (final ReflectiveOperationException ex) {
            throw new JobSystemException("Elastic-Job: Class '%s' initialize failure, the error message is '%s'.", jobClass, ex.getMessage());
        }
    }
    
    @Override
    public void killTask(final ExecutorDriver executorDriver, final Protos.TaskID taskID) {
        executorDriver.sendStatusUpdate(Protos.TaskStatus.newBuilder().setTaskId(taskID).setState(Protos.TaskState.TASK_KILLED).build());
        executorDriver.stop();
    }
    
    @Override
    public void frameworkMessage(final ExecutorDriver executorDriver, final byte[] bytes) {
    }

    @Override
    public void shutdown(final ExecutorDriver executorDriver) {
    }

    @Override
    public void error(final ExecutorDriver executorDriver, final String s) {
    }
}
