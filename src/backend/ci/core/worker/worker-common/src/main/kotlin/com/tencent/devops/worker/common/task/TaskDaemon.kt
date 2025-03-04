/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.worker.common.task

import com.tencent.devops.common.api.exception.TaskExecuteException
import com.tencent.devops.common.api.pojo.ErrorCode
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.process.pojo.BuildTask
import com.tencent.devops.process.pojo.BuildTaskResult
import com.tencent.devops.process.pojo.BuildVariables
import com.tencent.devops.worker.common.logger.LoggerService
import com.tencent.devops.worker.common.utils.TaskUtil
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TaskDaemon(
    private val task: ITask,
    private val buildTask: BuildTask,
    private val buildVariables: BuildVariables,
    private val workspace: File
) : Callable<Map<String, String>> {
    override fun call(): Map<String, String> {
        return try {
            task.run(buildTask, buildVariables, workspace)
            task.getAllEnv()
        } catch (e: InterruptedException) {
            task.getAllEnv()
        }
    }

    fun run() {
        val timeout = TaskUtil.getTimeOut(buildTask)
        val taskDaemon = TaskDaemon(
            task = task,
            buildTask = buildTask,
            buildVariables = buildVariables,
            workspace = workspace
        )
        val executor = Executors.newCachedThreadPool()
        val taskId = buildTask.taskId
        if (taskId != null) {
            TaskExecutorCache.put(taskId, executor)
        }
        val f1 = executor.submit(taskDaemon)
        try {
            f1.get(timeout, TimeUnit.MINUTES) ?: throw TimeoutException("插件执行超时, 超时时间:${timeout}分钟")
        } catch (e: TimeoutException) {
            throw TaskExecuteException(
                errorType = ErrorType.USER,
                errorCode = ErrorCode.USER_TASK_OUTTIME_LIMIT,
                errorMsg = e.message ?: "插件执行超时, 超时时间:${timeout}分钟"
            )
        } finally {
            executor.shutdownNow()
            if (taskId != null) {
                TaskExecutorCache.invalidate(taskId)
            }
        }
    }

    private fun getAllEnv(): Map<String, String> {
        return task.getAllEnv()
    }

    private fun getMonitorData(): Map<String, Any> {
        return task.getMonitorData()
    }

    fun getBuildResult(
        isSuccess: Boolean = true,
        errorMessage: String? = null,
        errorType: String? = null,
        errorCode: Int? = 0
    ): BuildTaskResult {

        val allEnv = getAllEnv()
        val buildResult = mutableMapOf<String, String>()
        if (allEnv.isNotEmpty()) {
            allEnv.forEach { (key, value) ->
                if (value.length > PARAM_MAX_LENGTH) {
                    LoggerService.addYellowLine("[${buildTask.taskId}]|ABANDON_DATA|len[$key]=${value.length}" +
                        "(max=$PARAM_MAX_LENGTH)")
                    return@forEach
                }
                buildResult[key] = value
            }
        }

        return BuildTaskResult(
            taskId = buildTask.taskId!!,
            elementId = buildTask.elementId!!,
            containerId = buildVariables.containerHashId,
            success = isSuccess,
            buildResult = buildResult,
            message = errorMessage,
            type = buildTask.type,
            errorType = errorType,
            errorCode = errorCode,
            monitorData = getMonitorData()
        )
    }

    companion object {
        private const val PARAM_MAX_LENGTH = 4000 // 流水线参数最大长度
    }
}
