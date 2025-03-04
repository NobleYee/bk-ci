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

package com.tencent.devops.process.service.builds

import com.fasterxml.jackson.core.type.TypeReference
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.ParamBlankException
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.pojo.BuildHistoryPage
import com.tencent.devops.common.api.pojo.ErrorType
import com.tencent.devops.common.api.pojo.IdValue
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.api.pojo.SimpleResult
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.pipeline.PipelineEventDispatcher
import com.tencent.devops.common.event.enums.ActionType
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.enums.BuildFormPropertyType
import com.tencent.devops.common.pipeline.enums.BuildPropertyType
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.enums.ManualReviewAction
import com.tencent.devops.common.pipeline.enums.StartType
import com.tencent.devops.common.pipeline.pojo.BuildFormProperty
import com.tencent.devops.common.pipeline.pojo.BuildParameters
import com.tencent.devops.common.pipeline.pojo.StageReviewRequest
import com.tencent.devops.common.pipeline.pojo.element.agent.ManualReviewUserTaskElement
import com.tencent.devops.common.pipeline.pojo.element.atom.ManualReviewParam
import com.tencent.devops.common.pipeline.pojo.element.atom.ManualReviewParamType
import com.tencent.devops.common.pipeline.pojo.element.trigger.ManualTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.RemoteTriggerElement
import com.tencent.devops.common.pipeline.utils.SkipElementUtils
import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.HomeHostUtil
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.engine.compatibility.BuildParametersCompatibilityTransformer
import com.tencent.devops.process.engine.compatibility.BuildPropertyCompatibilityTools
import com.tencent.devops.process.engine.control.lock.BuildIdLock
import com.tencent.devops.process.engine.control.lock.PipelineBuildRunLock
import com.tencent.devops.process.engine.exception.BuildTaskException
import com.tencent.devops.process.engine.interceptor.InterceptData
import com.tencent.devops.process.engine.interceptor.PipelineInterceptorChain
import com.tencent.devops.process.engine.service.PipelineBuildDetailService
import com.tencent.devops.process.engine.service.PipelineBuildQualityService
import com.tencent.devops.process.engine.service.PipelineRepositoryService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.PipelineStageService
import com.tencent.devops.process.jmx.api.ProcessJmxApi
import com.tencent.devops.process.permission.PipelinePermissionService
import com.tencent.devops.process.pojo.BuildBasicInfo
import com.tencent.devops.process.pojo.BuildHistory
import com.tencent.devops.process.pojo.BuildHistoryVariables
import com.tencent.devops.process.pojo.BuildHistoryWithPipelineVersion
import com.tencent.devops.process.pojo.BuildHistoryWithVars
import com.tencent.devops.process.pojo.BuildManualStartupInfo
import com.tencent.devops.process.pojo.RedisAtomsBuild
import com.tencent.devops.process.pojo.ReviewParam
import com.tencent.devops.process.pojo.SecretInfo
import com.tencent.devops.process.pojo.VmInfo
import com.tencent.devops.process.pojo.mq.PipelineBuildContainerEvent
import com.tencent.devops.process.pojo.pipeline.ModelDetail
import com.tencent.devops.process.pojo.pipeline.PipelineLatestBuild
import com.tencent.devops.process.service.BuildStartupParamService
import com.tencent.devops.process.service.BuildVariableService
import com.tencent.devops.process.service.ParamFacadeService
import com.tencent.devops.process.service.PipelineTaskPauseService
import com.tencent.devops.process.service.pipeline.PipelineBuildService
import com.tencent.devops.process.util.BuildMsgUtils
import com.tencent.devops.process.utils.PIPELINE_BUILD_MSG
import com.tencent.devops.process.utils.PIPELINE_RETRY_ALL_FAILED_CONTAINER
import com.tencent.devops.process.utils.PIPELINE_RETRY_BUILD_ID
import com.tencent.devops.process.utils.PIPELINE_RETRY_COUNT
import com.tencent.devops.process.utils.PIPELINE_RETRY_START_TASK_ID
import com.tencent.devops.process.utils.PIPELINE_SKIP_FAILED_TASK
import com.tencent.devops.process.utils.PIPELINE_START_TYPE
import com.tencent.devops.store.api.atom.ServiceMarketAtomEnvResource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import javax.ws.rs.NotFoundException
import javax.ws.rs.core.Response
import javax.ws.rs.core.UriBuilder

/**
 *
 * @version 1.0
 */
@Suppress("ALL")
@Service
class PipelineBuildFacadeService(
    private val pipelineEventDispatcher: PipelineEventDispatcher,
    private val pipelineInterceptorChain: PipelineInterceptorChain,
    private val pipelineBuildService: PipelineBuildService,
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val buildVariableService: BuildVariableService,
    private val pipelineStageService: PipelineStageService,
    private val redisOperation: RedisOperation,
    private val buildDetailService: PipelineBuildDetailService,
    private val pipelineTaskPauseService: PipelineTaskPauseService,
    private val jmxApi: ProcessJmxApi,
    private val pipelinePermissionService: PipelinePermissionService,
    private val buildStartupParamService: BuildStartupParamService,
    private val pipelineBuildQualityService: PipelineBuildQualityService,
    private val paramFacadeService: ParamFacadeService,
    private val buildLogPrinter: BuildLogPrinter,
    private val buildParamCompatibilityTransformer: BuildParametersCompatibilityTransformer,
    private val client: Client
) {
    companion object {
        private val logger = LoggerFactory.getLogger(PipelineBuildFacadeService::class.java)
    }

    private fun filterParams(
        userId: String?,
        projectId: String,
        pipelineId: String,
        params: List<BuildFormProperty>
    ): List<BuildFormProperty> {
        return paramFacadeService.filterParams(userId, projectId, pipelineId, params)
    }

    fun buildManualStartupInfo(
        userId: String?,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): BuildManualStartupInfo {

        if (checkPermission) { // 不用校验查看权限，只校验执行权限
            pipelinePermissionService.validPipelinePermission(
                userId = userId!!,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }

        pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId)
            )

        val model = getModel(projectId, pipelineId)

        val triggerContainer = model.stages[0].containers[0] as TriggerContainer

        var canManualStartup = false
        var canElementSkip = false
        var useLatestParameters = false
        run lit@{
            triggerContainer.elements.forEach {
                if (it is ManualTriggerElement && it.isElementEnable()) {
                    canManualStartup = true
                    canElementSkip = it.canElementSkip ?: false
                    useLatestParameters = it.useLatestParameters ?: false
                    return@lit
                }
            }
        }

        // 当使用最近一次参数进行构建的时候，获取并替换container.params中的defaultValue值
        if (useLatestParameters) {
            // 获取最后一次的构建id
            val lastTimeBuildInfo = pipelineRuntimeService.getLastTimeBuild(projectId, pipelineId)
            if (lastTimeBuildInfo != null) {
                val latestParamsStr = buildStartupParamService.getParam(lastTimeBuildInfo.buildId)
                // 为空的时候不处理
                if (latestParamsStr != null) {
                    val latestParams =
                        JsonUtil.to(latestParamsStr, object : TypeReference<MutableMap<String, Any>>() {})
                    triggerContainer.params.forEach { param ->
                        val realValue = latestParams[param.id]
                        if (realValue != null) {
                            // 有上一次的构建参数的时候才设置成默认值，否者依然使用默认值。
                            // 当值是boolean类型的时候，需要转为boolean类型
                            if (param.defaultValue is Boolean) {
                                param.defaultValue = realValue.toString().toBoolean()
                            } else {
                                param.defaultValue = realValue
                            }
                        }
                    }
                }
            }
        }

        // #2902 默认增加构建信息
        val params = mutableListOf(
            BuildFormProperty(
                id = PIPELINE_BUILD_MSG,
                required = true,
                type = BuildFormPropertyType.STRING,
                defaultValue = "",
                options = null,
                desc = MessageCodeUtil.getCodeLanMessage(
                    messageCode = ProcessMessageCode.BUILD_MSG_DESC
                ),
                repoHashId = null,
                relativePath = null,
                scmType = null,
                containerType = null,
                glob = null,
                properties = null,
                label = MessageCodeUtil.getCodeLanMessage(
                    messageCode = ProcessMessageCode.BUILD_MSG_LABEL,
                    defaultMessage = "构建信息"
                ),
                placeholder = MessageCodeUtil.getCodeLanMessage(
                    messageCode = ProcessMessageCode.BUILD_MSG_MANUAL,
                    defaultMessage = "手动触发"
                ),
                propertyType = BuildPropertyType.BUILD.name
            )
        )
        params.addAll(
            filterParams(
                userId = if (checkPermission && userId != null) userId else null,
                projectId = projectId,
                pipelineId = pipelineId,
                params = triggerContainer.params
            )
        )

        BuildPropertyCompatibilityTools.fix(params)

        val currentBuildNo = triggerContainer.buildNo
        if (currentBuildNo != null) {
            currentBuildNo.buildNo = pipelineRepositoryService.getBuildNo(pipelineId = pipelineId)
                ?: currentBuildNo.buildNo
        }

        return BuildManualStartupInfo(
            canManualStartup = canManualStartup,
            canElementSkip = canElementSkip,
            properties = params,
            buildNo = currentBuildNo
        )
    }

    fun getBuildParameters(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String
    ): List<BuildParameters> {

        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限获取流水线($pipelineId)信息"
        )

        return try {
            val startupParam = buildStartupParamService.getParam(buildId)
            if (startupParam == null || startupParam.isEmpty()) {
                emptyList()
            } else {
                try {
                    val map: Map<String, Any> = JsonUtil.toMap(startupParam)
                    map.map { transform ->
                        BuildParameters(transform.key, transform.value)
                    }.toList().filter { !it.key.startsWith(SkipElementUtils.prefix) }
                } catch (e: Exception) {
                    logger.warn("Fail to convert the parameters($startupParam) to map of build($buildId)", e)
                    throw e
                }
            }
        } catch (e: NotFoundException) {
            return emptyList()
        }
    }

    fun retry(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        taskId: String? = null,
        failedContainer: Boolean? = false,
        skipFailedTask: Boolean? = false,
        isMobile: Boolean = false,
        channelCode: ChannelCode? = ChannelCode.BS,
        checkPermission: Boolean? = true
    ): String {
        if (checkPermission!!) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限重启流水线($pipelineId)"
            )
        }

        val redisLock = BuildIdLock(redisOperation = redisOperation, buildId = buildId)
        try {

            redisLock.lock()

            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
                ?: throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                    defaultMessage = "构建任务${buildId}不存在",
                    params = arrayOf(buildId)
                )

            if (!buildInfo.status.isFinish() && buildInfo.status != BuildStatus.STAGE_SUCCESS) {
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.ERROR_DUPLICATE_BUILD_RETRY_ACT,
                    defaultMessage = "重试已经启动，忽略重复的请求"
                )
            }

            if (buildInfo.pipelineId != pipelineId) {
                throw ErrorCodeException(errorCode = ProcessMessageCode.ERROR_PIPLEINE_INPUT)
            }

            val readyToBuildPipelineInfo =
                pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
                    ?: throw ErrorCodeException(
                        statusCode = Response.Status.NOT_FOUND.statusCode,
                        errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                        defaultMessage = "流水线不存在",
                        params = arrayOf(buildId)
                    )

            if (!readyToBuildPipelineInfo.canManualStartup) {
                throw ErrorCodeException(
                    defaultMessage = "该流水线不能手动启动",
                    errorCode = ProcessMessageCode.DENY_START_BY_MANUAL
                )
            }

            val model = buildDetailService.getBuildModel(buildId) ?: throw ErrorCodeException(
                errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS
            )
            val startParamsWithType = mutableListOf<BuildParameters>()
            val originVars = buildVariableService.getAllVariable(buildId)
            if (!taskId.isNullOrBlank()) {
                // stage/job/task级重试，获取buildVariable构建参数，恢复环境变量
                originVars.forEach { (t, u) -> startParamsWithType.add(BuildParameters(key = t, value = u)) }

                // stage/job/task级重试
                run {
                    model.stages.forEach { s ->
                        // stage 级重试
                        if (s.id == taskId) {
                            startParamsWithType.add(BuildParameters(key = PIPELINE_RETRY_START_TASK_ID, value = s.id!!))
                            startParamsWithType.add(
                                BuildParameters(
                                    key = PIPELINE_RETRY_ALL_FAILED_CONTAINER,
                                    value = failedContainer ?: false,
                                    valueType = BuildFormPropertyType.TEMPORARY
                                )
                            )
                            startParamsWithType.add(
                                BuildParameters(
                                    key = PIPELINE_SKIP_FAILED_TASK,
                                    value = skipFailedTask ?: false,
                                    valueType = BuildFormPropertyType.TEMPORARY
                                )
                            )
                            return@run
                        }
                        s.containers.forEach { c ->
                            val pos = if (c.id == taskId) 0 else -1 // 容器job级别的重试，则找job的第一个原子
                            c.elements.forEachIndexed { index, element ->
                                if (index == pos) {
                                    startParamsWithType.add(
                                        BuildParameters(
                                            key = PIPELINE_RETRY_START_TASK_ID,
                                            value = element.id!!
                                        )
                                    )
                                    return@run
                                }
                                if (element.id == taskId) {
                                    startParamsWithType.add(
                                        BuildParameters(
                                            key = PIPELINE_RETRY_START_TASK_ID,
                                            value = element.id!!
                                        )
                                    )
                                    startParamsWithType.add(
                                        BuildParameters(
                                            key = PIPELINE_SKIP_FAILED_TASK,
                                            value = skipFailedTask ?: false,
                                            valueType = BuildFormPropertyType.TEMPORARY
                                        )
                                    )
                                    return@run
                                }
                            }
                        }
                    }
                }
            } else {
                // 完整构建重试，去掉启动参数中的重试插件ID保证不冲突，同时保留重试次数
                try {
                    val startupParam = buildStartupParamService.getParam(buildId)
                    if (startupParam != null && startupParam.isNotEmpty()) {
                        startParamsWithType.addAll(
                            JsonUtil.toMap(startupParam).filter { it.key != PIPELINE_RETRY_START_TASK_ID }.map {
                                BuildParameters(key = it.key, value = it.value)
                            }
                        )
                    }
                    // #4531 重试完整构建时将所有stage的审核状态恢复
                    pipelineStageService.retryRefreshStage(model)
                } catch (ignored: Exception) {
                    logger.warn("ENGINE|$buildId|Fail to get the startup param: $ignored")
                }
            }

            // 重置因暂停而变化的element(需同时支持流水线重试和stage重试, task重试), model不在这保存，在startBuild中保存
            pipelineTaskPauseService.resetElementWhenPauseRetry(buildId, model)

            logger.info(
                "ENGINE|$buildId|RETRY_PIPELINE_ORIGIN|taskId=$taskId|$pipelineId|" +
                        "retryCount=${originVars[PIPELINE_RETRY_COUNT]}|fc=$failedContainer|skip=$skipFailedTask"
            )

            // rebuild重试计数
            val retryCount = if (originVars[PIPELINE_RETRY_COUNT] != null) {
                originVars[PIPELINE_RETRY_COUNT].toString().toInt() + 1
            } else {
                1
            }
            startParamsWithType.add(BuildParameters(key = PIPELINE_RETRY_COUNT, value = retryCount))
            startParamsWithType.add(BuildParameters(key = PIPELINE_RETRY_BUILD_ID, value = buildId))

            return pipelineBuildService.startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = StartType.toStartType(originVars[PIPELINE_START_TYPE] ?: ""),
                startParamsWithType = startParamsWithType,
                channelCode = channelCode ?: ChannelCode.BS,
                isMobile = isMobile,
                model = model,
                signPipelineVersion = buildInfo.version,
                frequencyLimit = true,
                handlePostFlag = false
            )
        } finally {
            redisLock.unlock()
        }
    }

    fun buildManualStartup(
        userId: String,
        startType: StartType,
        projectId: String,
        pipelineId: String,
        values: Map<String, String>,
        channelCode: ChannelCode,
        checkPermission: Boolean = true,
        isMobile: Boolean = false,
        startByMessage: String? = null,
        buildNo: Int? = null,
        frequencyLimit: Boolean = true
    ): String {
        logger.info("Manual build start with value [$values][$buildNo]")
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }

        val readyToBuildPipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId)
            )

        val startEpoch = System.currentTimeMillis()
        try {

            val model = getModel(projectId, pipelineId)

            /**
             * 验证流水线参数构建启动参数
             */
            val triggerContainer = model.stages[0].containers[0] as TriggerContainer

            if (startType == StartType.MANUAL) {
                if (!readyToBuildPipelineInfo.canManualStartup) {
                    throw ErrorCodeException(
                        defaultMessage = "该流水线不能手动启动",
                        errorCode = ProcessMessageCode.DENY_START_BY_MANUAL
                    )
                }
            } else if (startType == StartType.REMOTE) {
                var canRemoteStartup = false
                run lit@{
                    triggerContainer.elements.forEach {
                        if (it is RemoteTriggerElement && it.isElementEnable()) {
                            canRemoteStartup = true
                            return@lit
                        }
                    }
                }

                if (!canRemoteStartup) {
                    throw ErrorCodeException(
                        defaultMessage = "该流水线不能远程触发",
                        errorCode = ProcessMessageCode.DENY_START_BY_REMOTE
                    )
                }
            }

            if (buildNo != null) {
                pipelineRuntimeService.updateBuildNo(pipelineId, buildNo)
                logger.info("[$pipelineId] buildNo was changed to [$buildNo]")
            }

            val startParamsWithType =
                buildParamCompatibilityTransformer.parseManualStartParam(triggerContainer.params, values)
            startParamsWithType.add(
                BuildParameters(
                    key = PIPELINE_BUILD_MSG,
                    value = BuildMsgUtils.getBuildMsg(
                        buildMsg = values[PIPELINE_BUILD_MSG],
                        startType = startType,
                        channelCode = channelCode
                    )
                )
            )

            return pipelineBuildService.startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = startType,
                startParamsWithType = startParamsWithType,
                channelCode = channelCode,
                isMobile = isMobile,
                model = model,
                frequencyLimit = frequencyLimit,
                buildNo = buildNo,
                startValues = values
            )
        } finally {
            logger.info("[$pipelineId]|$userId|It take(${System.currentTimeMillis() - startEpoch})ms to start pipeline")
        }
    }

    /**
     * 定时触发
     */
    fun timerTriggerPipelineBuild(
        userId: String,
        projectId: String,
        pipelineId: String,
        parameters: Map<String, Any> = emptyMap(),
        checkPermission: Boolean = true
    ): String? {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限启动流水线($pipelineId)"
            )
        }
        val readyToBuildPipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: return null
        val startEpoch = System.currentTimeMillis()
        try {

            val model = getModel(projectId, pipelineId, readyToBuildPipelineInfo.version)

            /**
             * 验证流水线参数构建启动参数
             */
            val triggerContainer = model.stages[0].containers[0] as TriggerContainer

            val startParams = mutableListOf<BuildParameters>()
            for (it in parameters) {
                startParams.add(BuildParameters(it.key, it.value))
            }
            val paramsKeyList = startParams.map { it.key }
            triggerContainer.params.forEach {
                if (paramsKeyList.contains(it.id)) {
                    return@forEach
                }
                startParams.add(BuildParameters(key = it.id, value = it.defaultValue, readOnly = it.readOnly))
            }
            // 子流水线的调用不受频率限制
            val startParamsWithType = mutableListOf<BuildParameters>()
            startParams.forEach { (key, value, valueType, readOnly) ->
                startParamsWithType.add(BuildParameters(
                    key,
                    value,
                    valueType,
                    readOnly
                ))
            }

            return pipelineBuildService.startPipeline(
                userId = userId,
                readyToBuildPipelineInfo = readyToBuildPipelineInfo,
                startType = StartType.TIME_TRIGGER,
                startParamsWithType = startParamsWithType,
                channelCode = readyToBuildPipelineInfo.channelCode,
                isMobile = false,
                model = model,
                signPipelineVersion = null,
                frequencyLimit = false
            )
        } finally {
            logger.info("Timer| It take(${System.currentTimeMillis() - startEpoch})ms to start pipeline($pipelineId)")
        }
    }

    fun buildManualShutdown(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ) {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EXECUTE,
                message = "用户（$userId) 无权限停止流水线($pipelineId)"
            )
        }

        buildManualShutdown(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            userId = userId,
            channelCode = channelCode
        )
    }

    fun buildManualReview(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        elementId: String,
        params: ReviewParam,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ) {

        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )

        if (buildInfo.pipelineId != pipelineId) {
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.ERROR_PIPLEINE_INPUT
            )
        }

        val model = pipelineRepositoryService.getModel(pipelineId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在"
        )
        // 对人工审核提交时的参数做必填和范围校验
        checkManualReviewParam(params = params.params)

        model.stages.forEachIndexed { index, s ->
            if (index == 0) {
                return@forEachIndexed
            }
            s.containers.forEach { cc ->
                cc.elements.forEach { el ->
                    if (el is ManualReviewUserTaskElement && el.id == elementId) {
                        // Replace the review user with environment
                        val reviewUser = mutableListOf<String>()
                        el.reviewUsers.forEach { user ->
                            reviewUser.addAll(buildVariableService.replaceTemplate(buildId, user).split(","))
                        }
                        params.params.forEach {
                            when (it.valueType) {
                                ManualReviewParamType.BOOLEAN -> {
                                    it.value = it.value ?: it.value.toString().toBoolean()
                                }
                                else -> {
                                    it.value = buildVariableService.replaceTemplate(buildId, it.value.toString())
                                }
                            }
                        }
                        if (!reviewUser.contains(userId)) {
                            throw ErrorCodeException(
                                statusCode = Response.Status.NOT_FOUND.statusCode,
                                errorCode = ProcessMessageCode.ERROR_QUALITY_REVIEWER_NOT_MATCH,
                                defaultMessage = "用户($userId)不在审核人员名单中",
                                params = arrayOf(userId)
                            )
                        }
                    }
                }
            }
        }
        logger.info("[$buildId]|buildManualReview|taskId=$elementId|userId=$userId|params=$params")
        pipelineRuntimeService.manualDealBuildTask(buildId, elementId, userId, params)
        if (params.status == ManualReviewAction.ABORT) {
            buildDetailService.updateBuildCancelUser(buildId, userId)
        }
    }

    fun buildManualStartStage(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        stageId: String,
        isCancel: Boolean,
        reviewRequest: StageReviewRequest?
    ) {
        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(buildId)
            )
        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )

        if (buildInfo.pipelineId != pipelineId) {
            logger.warn("[$buildId]|buildManualStartStage error|input=$pipelineId|pipeline=${buildInfo.pipelineId}")
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.ERROR_PIPLEINE_INPUT
            )
        }

        val buildStage = pipelineStageService.getStage(buildId, stageId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_STAGE_EXISTS_BY_ID,
                defaultMessage = "构建Stage${stageId}不存在",
                params = arrayOf(stageId)
            )

        if (buildStage.status.name != BuildStatus.PAUSE.name) throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_STAGE_IS_NOT_PAUSED,
            defaultMessage = "Stage($stageId)未处于暂停状态",
            params = arrayOf(stageId)
        )
        val group = buildStage.checkIn?.getReviewGroupById(reviewRequest?.id)
        if (group?.id != buildStage.checkIn?.groupToReview()?.id) {
            throw ErrorCodeException(
                statusCode = Response.Status.FORBIDDEN.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_STAGE_REVIEW_GROUP_NOT_FOUND,
                defaultMessage = "(${group?.name ?: "default"})非Stage($stageId)当前待审核组",
                params = arrayOf(stageId, reviewRequest?.id ?: "default")
            )
        }

        if (buildStage.checkIn?.reviewerContains(userId) != true) {
            throw ErrorCodeException(
                statusCode = Response.Status.FORBIDDEN.statusCode,
                errorCode = ProcessMessageCode.USER_NEED_PIPELINE_X_PERMISSION,
                defaultMessage = "用户($userId)不在Stage($stageId)当前审核组可执行名单",
                params = arrayOf(stageId)
            )
        }

        val runLock = PipelineBuildRunLock(redisOperation, pipelineId)
        try {
            runLock.lock()
            val interceptResult = pipelineInterceptorChain.filter(
                InterceptData(pipelineInfo, null, StartType.MANUAL)
            )

            if (interceptResult.isNotOk()) {
                // 发送排队失败的事件
                logger.warn("[$pipelineId]|START_PIPELINE_MANUAL|流水线启动失败:[${interceptResult.message}]")
                throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = interceptResult.status.toString(),
                    defaultMessage = "Stage启动失败![${interceptResult.message}]"
                )
            }
            val success = if (isCancel) {
                pipelineStageService.cancelStage(
                    userId = userId,
                    buildStage = buildStage,
                    reviewRequest = reviewRequest
                )
            } else {
                // TODO 暂时兼容前端显示的变量刷新，下次发版去掉
                buildStage.controlOption!!.stageControlOption.reviewParams = reviewRequest?.reviewParams
                buildStage.controlOption!!.stageControlOption.triggered = true
                pipelineStageService.startStage(
                    userId = userId,
                    buildStage = buildStage,
                    reviewRequest = reviewRequest
                )
            }
            if (!success) throw ErrorCodeException(
                statusCode = Response.Status.BAD_REQUEST.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPLEINE_INPUT,
                defaultMessage = "审核Stage($stageId)数据异常",
                params = arrayOf(stageId)
            )
        } finally {
            runLock.unlock()
        }
    }

    fun goToReview(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        elementId: String
    ): ReviewParam {

        pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )

        val model = pipelineRepositoryService.getModel(pipelineId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在"
        )

        model.stages.forEachIndexed { index, s ->
            if (index == 0) {
                return@forEachIndexed
            }
            s.containers.forEach { cc ->
                cc.elements.forEach { el ->
                    if (el is ManualReviewUserTaskElement && el.id == elementId) {
                        val reviewUser = mutableListOf<String>()
                        el.reviewUsers.forEach { user ->
                            reviewUser.addAll(buildVariableService.replaceTemplate(buildId, user).split(","))
                        }
                        el.params.forEach { param ->
                            when (param.valueType) {
                                ManualReviewParamType.BOOLEAN -> {
                                    param.value = param.value ?: param.value.toString().toBoolean()
                                }
                                else -> {
                                    param.value = buildVariableService.replaceTemplate(buildId, param.value.toString())
                                }
                            }
                        }
                        el.desc = buildVariableService.replaceTemplate(buildId, el.desc)
                        if (!reviewUser.contains(userId)) {
                            throw ErrorCodeException(
                                statusCode = Response.Status.NOT_FOUND.statusCode,
                                errorCode = ProcessMessageCode.ERROR_QUALITY_REVIEWER_NOT_MATCH,
                                defaultMessage = "用户($userId)不在审核人员名单中",
                                params = arrayOf(userId)
                            )
                        }
                        val reviewParam =
                            ReviewParam(projectId, pipelineId, buildId, reviewUser, null, el.desc, "", el.params)
                        logger.info("reviewParam : $reviewParam")
                        return reviewParam
                    }
                }
            }
        }
        return ReviewParam()
    }

    fun serviceShutdown(projectId: String, pipelineId: String, buildId: String, channelCode: ChannelCode) {
        val redisLock = RedisLock(redisOperation, "process.pipeline.build.shutdown.$buildId", 10)
        try {
            redisLock.lock()

            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)

            if (buildInfo == null) {
                return
            } else {
                if (buildInfo.parentBuildId != null && buildInfo.parentBuildId != buildId) {
                    if (StartType.PIPELINE.name == buildInfo.trigger) {
                        if (buildInfo.parentTaskId != null) {
                            val superPipeline = pipelineRuntimeService.getBuildInfo(buildInfo.parentBuildId!!)
                            if (superPipeline != null) {
                                serviceShutdown(
                                    projectId = projectId,
                                    pipelineId = superPipeline.pipelineId,
                                    buildId = superPipeline.buildId,
                                    channelCode = channelCode
                                )
                            }
                        }
                    }
                }
            }

            try {
                pipelineRuntimeService.cancelBuild(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    buildId = buildId,
                    userId = buildInfo.startUser,
                    buildStatus = BuildStatus.FAILED
                )
                buildDetailService.updateBuildCancelUser(buildId = buildId, cancelUserId = buildInfo.startUser)
                logger.info("$pipelineId|CANCEL_PIPELINE_BUILD|buildId=$buildId|user=${buildInfo.startUser}")
            } catch (t: Throwable) {
                logger.warn("Fail to shutdown the build($buildId) of pipeline($pipelineId)", t)
            }
        } finally {
            redisLock.unlock()
        }
    }

    fun getBuildDetail(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): ModelDetail {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
            )
        }

        return getBuildDetail(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            channelCode = channelCode
        )
    }

    fun getBuildDetail(
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode
    ): ModelDetail {
        val newModel = buildDetailService.get(buildId) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在"
        )

        if (newModel.pipelineId != pipelineId) {
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线编排不存在"
            )
        }

        pipelineBuildQualityService.addQualityGateReviewUsers(projectId, pipelineId, buildId, newModel.model)

        return newModel
    }

    fun getBuildDetailByBuildNo(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildNo: Int,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): ModelDetail {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
        )
        val buildId = pipelineRuntimeService.getBuildIdbyBuildNo(projectId, pipelineId, buildNo)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建号($buildNo)不存在",
                params = arrayOf("buildNo=$buildNo")
            )
        return getBuildDetail(
            projectId = projectId, pipelineId = pipelineId, buildId = buildId, channelCode = channelCode
        )
    }

    fun goToLatestFinishedBuild(
        userId: String,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): Response {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
            )
        }
        val buildId = pipelineRuntimeService.getLatestFinishedBuildId(projectId, pipelineId)
        val apiDomain = HomeHostUtil.innerServerHost()
        val redirectURL = when (buildId) {
            null -> "$apiDomain/console/pipeline/$projectId/$pipelineId/history"
            else -> "$apiDomain/console/pipeline/$projectId/$pipelineId/detail/$buildId"
        }
        val uri = UriBuilder.fromUri(redirectURL).build()
        return Response.temporaryRedirect(uri).build()
    }

    fun getBuildStatusWithVars(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): BuildHistoryWithVars {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)构建状态"
            )
        }

        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(setOf(buildId))

        if (buildHistories.isEmpty()) {
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )
        }
        val buildHistory = buildHistories[0]
        val variables = buildVariableService.getAllVariable(buildId)
        return BuildHistoryWithVars(
            id = buildHistory.id,
            userId = buildHistory.userId,
            trigger = buildHistory.trigger,
            buildNum = buildHistory.buildNum,
            pipelineVersion = buildHistory.pipelineVersion,
            startTime = buildHistory.startTime,
            endTime = buildHistory.endTime,
            status = buildHistory.status,
            stageStatus = buildHistory.stageStatus,
            deleteReason = buildHistory.deleteReason,
            currentTimestamp = buildHistory.currentTimestamp,
            isMobileStart = buildHistory.isMobileStart,
            material = buildHistory.material,
            queueTime = buildHistory.queueTime,
            artifactList = buildHistory.artifactList,
            remark = buildHistory.remark,
            totalTime = buildHistory.totalTime,
            executeTime = buildHistory.executeTime,
            buildParameters = buildHistory.buildParameters,
            webHookType = buildHistory.webHookType,
            startType = buildHistory.startType,
            recommendVersion = buildHistory.recommendVersion,
            variables = variables,
            buildMsg = buildHistory.buildMsg,
            retry = buildHistory.retry,
            errorInfoList = buildHistory.errorInfoList,
            buildNumAlias = buildHistory.buildNumAlias,
            webhookInfo = buildHistory.webhookInfo
        )
    }

    fun getBuildVars(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        checkPermission: Boolean
    ): Result<BuildHistoryVariables> {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)构建变量"
            )
        }

        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(setOf(buildId))

        if (buildHistories.isEmpty()) {
            return MessageCodeUtil.generateResponseDataObject(
                ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                arrayOf(buildId)
            )
        }

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: return MessageCodeUtil.generateResponseDataObject(
                ProcessMessageCode.ERROR_NO_PIPELINE_EXISTS_BY_ID,
                arrayOf(buildId)
            )

        val allVariable = buildVariableService.getAllVariable(buildId)

        return Result(
            BuildHistoryVariables(
                id = buildHistories[0].id,
                userId = buildHistories[0].userId,
                trigger = buildHistories[0].trigger,
                pipelineName = pipelineInfo.pipelineName,
                buildNum = buildHistories[0].buildNum ?: 1,
                pipelineVersion = buildHistories[0].pipelineVersion,
                status = buildHistories[0].status,
                startTime = buildHistories[0].startTime,
                endTime = buildHistories[0].endTime,
                variables = allVariable
            )
        )
    }

    fun getBuildVarsByNames(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        variableNames: List<String>,
        checkPermission: Boolean
    ): Map<String, String> {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId) 构建变量的值"
            )
        }

        val allVariable = buildVariableService.getAllVariable(buildId)

        val varMap = HashMap<String, String>()
        variableNames.forEach {
            varMap[it] = (allVariable[it] ?: "")
        }
        return varMap
    }

    fun getBatchBuildStatus(
        projectId: String,
        buildIdSet: Set<String>,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): List<BuildHistory> {
        val buildHistories = pipelineRuntimeService.getBuildHistoryByIds(buildIdSet)

        if (buildHistories.isEmpty()) {
            return emptyList()
        }
        return buildHistories
    }

    fun getHistoryBuild(
        userId: String?,
        projectId: String,
        pipelineId: String,
        page: Int?,
        pageSize: Int?,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): BuildHistoryPage<BuildHistory> {
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: 1000
        val sqlLimit =
            if (pageSizeNotNull != -1) PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull) else null
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 1000

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId)
            )

        val apiStartEpoch = System.currentTimeMillis()
        try {
            if (checkPermission) {
                pipelinePermissionService.validPipelinePermission(
                    userId = userId!!,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.VIEW,
                    message = "用户（$userId) 无权限获取流水线($pipelineId)历史构建"
                )
            }

            val newTotalCount = pipelineRuntimeService.getPipelineBuildHistoryCount(projectId, pipelineId)
            val newHistoryBuilds = pipelineRuntimeService.listPipelineBuildHistory(projectId, pipelineId, offset, limit)
            val buildHistories = mutableListOf<BuildHistory>()
            buildHistories.addAll(newHistoryBuilds)
            val count = newTotalCount + 0L
            // 获取流水线版本号
            val result = BuildHistoryWithPipelineVersion(
                history = SQLPage(count, buildHistories),
                hasDownloadPermission = !checkPermission || pipelinePermissionService.checkPipelinePermission(
                    userId = userId!!,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.EXECUTE
                ),
                pipelineVersion = pipelineInfo.version
            )
            return BuildHistoryPage(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = result.history.count,
                records = result.history.records,
                hasDownloadPermission = result.hasDownloadPermission,
                pipelineVersion = result.pipelineVersion
            )
        } finally {
            jmxApi.execute(ProcessJmxApi.LIST_NEW_BUILDS_DETAIL, System.currentTimeMillis() - apiStartEpoch)
        }
    }

    fun getHistoryBuild(
        userId: String?,
        projectId: String,
        pipelineId: String,
        page: Int?,
        pageSize: Int?,
        materialAlias: List<String>?,
        materialUrl: String?,
        materialBranch: List<String>?,
        materialCommitId: String?,
        materialCommitMessage: String?,
        status: List<BuildStatus>?,
        trigger: List<StartType>?,
        queueTimeStartTime: Long?,
        queueTimeEndTime: Long?,
        startTimeStartTime: Long?,
        startTimeEndTime: Long?,
        endTimeStartTime: Long?,
        endTimeEndTime: Long?,
        totalTimeMin: Long?,
        totalTimeMax: Long?,
        remark: String?,
        buildNoStart: Int?,
        buildNoEnd: Int?,
        buildMsg: String? = null
    ): BuildHistoryPage<BuildHistory> {
        val pageNotNull = page ?: 0
        var pageSizeNotNull = pageSize ?: 50
        if (pageNotNull > 50) {
            pageSizeNotNull = 50
        }
        val sqlLimit =
            if (pageSizeNotNull != -1) PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull) else null
        val offset = sqlLimit?.offset ?: 0
        val limit = sqlLimit?.limit ?: 50

        val channelCode = if (projectId.startsWith("git_")) ChannelCode.GIT else ChannelCode.BS

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId, channelCode)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在",
                params = arrayOf(pipelineId)
            )

        val apiStartEpoch = System.currentTimeMillis()
        try {
            pipelinePermissionService.validPipelinePermission(
                userId = userId!!,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)历史构建"
            )

            val newTotalCount = pipelineRuntimeService.getPipelineBuildHistoryCount(
                projectId = projectId,
                pipelineId = pipelineId,
                materialAlias = materialAlias,
                materialUrl = materialUrl,
                materialBranch = materialBranch,
                materialCommitId = materialCommitId,
                materialCommitMessage = materialCommitMessage,
                status = status,
                trigger = trigger,
                queueTimeStartTime = queueTimeStartTime,
                queueTimeEndTime = queueTimeEndTime,
                startTimeStartTime = startTimeStartTime,
                startTimeEndTime = startTimeEndTime,
                endTimeStartTime = endTimeStartTime,
                endTimeEndTime = endTimeEndTime,
                totalTimeMin = totalTimeMin,
                totalTimeMax = totalTimeMax,
                remark = remark,
                buildNoStart = buildNoStart,
                buildNoEnd = buildNoEnd,
                buildMsg = buildMsg
            )

            val newHistoryBuilds = pipelineRuntimeService.listPipelineBuildHistory(
                projectId = projectId,
                pipelineId = pipelineId,
                offset = offset,
                limit = limit,
                materialAlias = materialAlias,
                materialUrl = materialUrl,
                materialBranch = materialBranch,
                materialCommitId = materialCommitId,
                materialCommitMessage = materialCommitMessage,
                status = status,
                trigger = trigger,
                queueTimeStartTime = queueTimeStartTime,
                queueTimeEndTime = queueTimeEndTime,
                startTimeStartTime = startTimeStartTime,
                startTimeEndTime = startTimeEndTime,
                endTimeStartTime = endTimeStartTime,
                endTimeEndTime = endTimeEndTime,
                totalTimeMin = totalTimeMin,
                totalTimeMax = totalTimeMax,
                remark = remark,
                buildNoStart = buildNoStart,
                buildNoEnd = buildNoEnd,
                buildMsg = buildMsg
            )
            val buildHistories = mutableListOf<BuildHistory>()
            buildHistories.addAll(newHistoryBuilds)
            val count = newTotalCount + 0L
            // 获取流水线版本号
            val result = BuildHistoryWithPipelineVersion(
                history = SQLPage(count, buildHistories),
                hasDownloadPermission = pipelinePermissionService.checkPipelinePermission(
                    userId = userId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.EXECUTE
                ),
                pipelineVersion = pipelineInfo.version
            )
            return BuildHistoryPage(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = result.history.count,
                records = result.history.records,
                hasDownloadPermission = result.hasDownloadPermission,
                pipelineVersion = result.pipelineVersion
            )
        } finally {
            jmxApi.execute(ProcessJmxApi.LIST_NEW_BUILDS_DETAIL, System.currentTimeMillis() - apiStartEpoch)
        }
    }

    fun updateRemark(userId: String, projectId: String, pipelineId: String, buildId: String, remark: String?) {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.EXECUTE,
            message = "用户（$userId) 没有流水线($pipelineId)的执行权限，无法修改备注"
        )
        pipelineRuntimeService.updateBuildRemark(projectId, pipelineId, buildId, remark)
    }

    fun getHistoryConditionStatus(userId: String, projectId: String, pipelineId: String): List<IdValue> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        val result = mutableListOf<IdValue>()
        BuildStatus.values().filter { it.visible }.forEach {
            result.add(IdValue(it.name, MessageCodeUtil.getMessageByLocale(it.statusName, it.name)))
        }
        return result
    }

    fun getHistoryConditionTrigger(userId: String, projectId: String, pipelineId: String): List<IdValue> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        return StartType.getStartTypeMap()
    }

    fun getHistoryConditionRepo(userId: String, projectId: String, pipelineId: String): List<String> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        return pipelineRuntimeService.getHistoryConditionRepo(projectId, pipelineId)
    }

    fun getHistoryConditionBranch(
        userId: String,
        projectId: String,
        pipelineId: String,
        alias: List<String>?
    ): List<String> {
        pipelinePermissionService.validPipelinePermission(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            permission = AuthPermission.VIEW,
            message = "用户（$userId) 无权限查看流水线($pipelineId)历史构建"
        )
        return pipelineRuntimeService.getHistoryConditionBranch(projectId, pipelineId, alias)
    }

    fun serviceBuildBasicInfo(buildId: String): BuildBasicInfo {
        val build = pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )
        return BuildBasicInfo(
            buildId = buildId,
            projectId = build.projectId,
            pipelineId = build.pipelineId,
            pipelineVersion = build.version
        )
    }

    fun batchServiceBasic(buildIds: Set<String>): Map<String, BuildBasicInfo> {
        val buildBasicInfoMap = pipelineRuntimeService.getBuildBasicInfoByIds(buildIds)
        if (buildBasicInfoMap.isEmpty()) {
            return emptyMap()
        }
        return buildBasicInfoMap
    }

    fun getSingleHistoryBuild(
        projectId: String,
        pipelineId: String,
        buildNum: Int,
        channelCode: ChannelCode
    ): BuildHistory? {
        val statusSet = mutableSetOf<BuildStatus>()
        if (buildNum == -1) {
            BuildStatus.values().forEach { status ->
                if (status.isFinish()) {
                    statusSet.add(status)
                } else if (status.isRunning()) {
                    statusSet.add(status)
                }
            }
        }
        val buildHistory = pipelineRuntimeService.getBuildHistoryByBuildNum(
            projectId = projectId,
            pipelineId = pipelineId,
            buildNum = buildNum,
            statusSet = statusSet
        )
        logger.info("[$pipelineId]|buildHistory=$buildHistory")
        return buildHistory
    }

    fun getLatestSuccessBuild(
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode
    ): BuildHistory? {
        val buildHistory = pipelineRuntimeService.getBuildHistoryByBuildNum(
            projectId = projectId,
            pipelineId = pipelineId,
            buildNum = -1,
            statusSet = setOf(BuildStatus.SUCCEED)
        )
        logger.info("[$pipelineId]|buildHistory=$buildHistory")
        return buildHistory
    }

    fun getModel(projectId: String, pipelineId: String, version: Int? = null) =
        pipelineRepositoryService.getModel(pipelineId, version) ?: throw ErrorCodeException(
            statusCode = Response.Status.NOT_FOUND.statusCode,
            errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
            defaultMessage = "流水线编排不存在"
        )

    fun updateRedisAtoms(
        buildId: String,
        projectId: String,
        redisAtomsBuild: RedisAtomsBuild
    ): Boolean {
        // 确定流水线是否在运行中
        val buildStatus = getBuildDetailStatus(
            userId = redisAtomsBuild.userId!!,
            projectId = projectId,
            pipelineId = redisAtomsBuild.pipelineId,
            buildId = buildId,
            channelCode = ChannelCode.BS,
            checkPermission = false
        )

        if (!BuildStatus.parse(buildStatus).isRunning()) {
            logger.error("$buildId|${redisAtomsBuild.vmSeqId} updateRedisAtoms failed, pipeline is not running.")
            throw ErrorCodeException(
                statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_IS_RUNNING_LOCK,
                defaultMessage = "流水线不在运行中"
            )
        }

        // 查看项目是否具有插件的权限
        val incrementAtoms = mutableMapOf<String, String>()
        val storeEnvResource = client.get(ServiceMarketAtomEnvResource::class)
        redisAtomsBuild.atoms.forEach { (atomCode, _) ->
            val atomEnvResult = storeEnvResource.getAtomEnv(projectId, atomCode = atomCode, version = "*")
            val atomEnv = atomEnvResult.data
            if (atomEnvResult.isNotOk() || atomEnv == null) {
                val message =
                    "Can not found atom($atomCode) in $projectId| ${atomEnvResult.message}, " +
                            "please check if the plugin is installed."
                throw BuildTaskException(
                    errorType = ErrorType.USER,
                    errorCode = ProcessMessageCode.ERROR_ATOM_NOT_FOUND.toInt(),
                    errorMsg = message,
                    pipelineId = redisAtomsBuild.pipelineId,
                    buildId = buildId,
                    taskId = ""
                )
            }

            // 重新组装RedisBuild atoms，projectCode为jfrog插件目录
            incrementAtoms[atomCode] = atomEnv.projectCode!!
        }

        // 从redis缓存中获取secret信息
        val result = redisOperation.hget(
            key = secretInfoRedisKey(buildId = buildId),
            hashKey = secretInfoRedisMapKey(
                vmSeqId = redisAtomsBuild.vmSeqId,
                executeCount = redisAtomsBuild.executeCount ?: 1
            )
        )
        if (result != null) {
            val secretInfo = JsonUtil.to(result, SecretInfo::class.java)
            logger.info("$buildId|${redisAtomsBuild.vmSeqId} updateRedisAtoms secretInfo: $secretInfo")
            val redisBuildAuthStr = redisOperation.get(redisKey(secretInfo.hashId, secretInfo.secretKey))
            if (redisBuildAuthStr != null) {
                val redisBuildAuth = JsonUtil.to(redisBuildAuthStr, RedisAtomsBuild::class.java)
                val newRedisBuildAuth = redisBuildAuth.copy(atoms = redisBuildAuth.atoms.plus(incrementAtoms))
                redisOperation.set(
                    key = redisKey(hashId = secretInfo.hashId, secretKey = secretInfo.secretKey),
                    value = JsonUtil.toJson(newRedisBuildAuth)
                )
            } else {
                logger.error("buildId|${redisAtomsBuild.vmSeqId} updateRedisAtoms failed, no redisBuild in redis.")
                throw ErrorCodeException(
                    statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                    errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                    defaultMessage = "没有redis缓存信息(redisBuild)"
                )
            }
        } else {
            logger.error("$buildId|${redisAtomsBuild.vmSeqId} updateRedisAtoms failed, no secretInfo in redis.")
            throw ErrorCodeException(
                statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "没有redis缓存信息(secretInfo)"
            )
        }

        return true
    }

    private fun secretInfoRedisKey(buildId: String) =
        "secret_info_key_$buildId"

    private fun redisKey(hashId: String, secretKey: String) =
        "docker_build_key_${hashId}_$secretKey"

    private fun secretInfoRedisMapKey(vmSeqId: String, executeCount: Int) = "$vmSeqId-$executeCount"

    private fun buildManualShutdown(
        projectId: String,
        pipelineId: String,
        buildId: String,
        userId: String,
        channelCode: ChannelCode
    ) {

        val redisLock = BuildIdLock(redisOperation = redisOperation, buildId = buildId)
        try {
            redisLock.lock()

            val modelDetail = buildDetailService.get(buildId)
                ?: return
            val alreadyCancelUser = modelDetail.cancelUserId

            if (BuildStatus.parse(modelDetail.status).isFinish()) {
                logger.warn("The build $buildId of project $projectId already finished ")
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.CANCEL_BUILD_BY_OTHER_USER,
                    defaultMessage = "流水线已经被取消构建或已完成",
                    params = arrayOf(alreadyCancelUser ?: "")
                )
            }

            if (modelDetail.pipelineId != pipelineId) {
                logger.warn("shutdown error: input|$pipelineId| buildId-pipeline| ${modelDetail.pipelineId}| $buildId")
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.ERROR_PIPLEINE_INPUT
                )
            }
            // 兼容post任务的场景，处于”运行中“的构建可以支持多次取消操作
            val cancelFlag = redisOperation.get("${BuildStatus.CANCELED.name}_$buildId")?.toBoolean()
            if (cancelFlag == true) {
                logger.warn("The build $buildId of project $projectId already cancel by user $alreadyCancelUser")
                throw ErrorCodeException(
                    errorCode = ProcessMessageCode.CANCEL_BUILD_BY_OTHER_USER,
                    defaultMessage = "流水线已经被${alreadyCancelUser}取消构建",
                    params = arrayOf(userId)
                )
            }

            val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)

            if (pipelineInfo == null) {
                logger.warn("The pipeline($pipelineId) of project($projectId) is not exist")
                return
            }
            if (pipelineInfo.channelCode != channelCode) {
                return
            }

            val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
            if (buildInfo == null) {
                logger.warn("The build($buildId) of pipeline($pipelineId) is not exist")
                throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                    defaultMessage = "构建任务${buildId}不存在",
                    params = arrayOf(buildId)
                )
            }

            val tasks = getRunningTask(projectId, buildId)

            tasks.forEach { task ->
                val taskId = task["taskId"] ?: ""
                val containerId = task["containerId"] ?: ""
                val status = task["status"] ?: ""
                val executeCount = task["executeCount"] ?: 1
                logger.info("build($buildId) shutdown by $userId, taskId: $taskId, status: $status")
                buildLogPrinter.addYellowLine(
                    buildId = buildId,
                    message = "Run cancelled by $userId",
                    tag = taskId.toString(),
                    jobId = containerId.toString(),
                    executeCount = executeCount as Int
                )
            }

            if (tasks.isEmpty()) {
                buildLogPrinter.addYellowLine(
                    buildId = buildId,
                    message = "Run cancelled by $userId",
                    tag = "",
                    jobId = "",
                    executeCount = 1
                )
            }

            try {
                pipelineRuntimeService.cancelBuild(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    buildId = buildId,
                    userId = userId,
                    buildStatus = BuildStatus.CANCELED
                )
                buildDetailService.updateBuildCancelUser(buildId = buildId, cancelUserId = userId)
                logger.info("Cancel the pipeline($pipelineId) of instance($buildId) by the user($userId)")
            } catch (t: Throwable) {
                logger.warn("Fail to shutdown the build($buildId) of pipeline($pipelineId)", t)
            }
        } finally {
            redisLock.unlock()
        }
    }

    private fun getRunningTask(projectId: String, buildId: String): List<Map<String, Any>> {
        return pipelineRuntimeService.getRunningTask(projectId, buildId)
    }

    fun getPipelineLatestBuildByIds(projectId: String, pipelineIds: List<String>): Map<String, PipelineLatestBuild> {
        logger.info("getPipelineLatestBuildByIds: $projectId | $pipelineIds")

        return pipelineRuntimeService.getLatestBuild(projectId, pipelineIds)
    }

    fun workerBuildFinish(
        projectCode: String,
        pipelineId: String, /* pipelineId在agent请求的数据有值前不可用 */
        buildId: String,
        vmSeqId: String,
        simpleResult: SimpleResult
    ) {
        // success do nothing just log
        if (simpleResult.success) {
            logger.info("[$buildId]|Job#$vmSeqId|${simpleResult.success}| worker had been exit.")
            return
        }

        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
        if (buildInfo == null || buildInfo.status.isFinish()) {
            logger.warn("[$buildId]|workerBuildFinish|The build status is ${buildInfo?.status}")
            return
        }

        val container = pipelineRuntimeService.getContainer(buildId = buildId, stageId = null, containerId = vmSeqId)
        if (container != null) {
            val stage = pipelineStageService.getStage(buildId = buildId, stageId = container.stageId)
            if (stage != null && stage.status.isRunning()) { // Stage 未处于运行中，不接受下面容器结束事件
                val msg = "Job#$vmSeqId's worker exception: ${simpleResult.message}"
                logger.info("[$buildId]|Job#$vmSeqId|${simpleResult.success}|$msg")
                pipelineEventDispatcher.dispatch(
                    PipelineBuildContainerEvent(
                        source = "worker_build_finish",
                        projectId = buildInfo.projectId,
                        pipelineId = buildInfo.pipelineId,
                        userId = buildInfo.startUser,
                        buildId = buildId,
                        stageId = container.stageId,
                        containerId = vmSeqId,
                        containerType = container.containerType,
                        actionType = ActionType.TERMINATE,
                        reason = msg
                    )
                )
            }
        }
    }

    fun saveBuildVmInfo(projectId: String, pipelineId: String, buildId: String, vmSeqId: String, vmInfo: VmInfo) {
        buildDetailService.saveBuildVmInfo(
            projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            containerId = vmSeqId.toInt(),
            vmInfo = vmInfo
        )
    }

    fun getBuildDetailStatus(
        userId: String,
        projectId: String,
        pipelineId: String,
        buildId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): String {
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户（$userId) 无权限获取流水线($pipelineId)详情"
            )
        }
        return pipelineRuntimeService.getBuildInfo(buildId)?.status?.name
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "流水线构建[$buildId]不存在",
                params = arrayOf(buildId)
            )
    }

    private fun checkManualReviewParamOut(
        type: ManualReviewParamType,
        originParam: ManualReviewParam,
        param: String
    ) {
        when (type) {
            ManualReviewParamType.MULTIPLE -> {
                if (!originParam.options!!.map { it.key }.toList().containsAll(param.split(","))) {
                    throw ParamBlankException("param: ${originParam.key} value not in multipleParams")
                }
            }
            ManualReviewParamType.ENUM -> {
                if (!originParam.options!!.map { it.key }.toList().contains(param)) {
                    throw ParamBlankException("param: ${originParam.key} value not in enumParams")
                }
            }
            ManualReviewParamType.BOOLEAN -> {
                originParam.value = param.toBoolean()
            }
            else -> {
                originParam.value = param
            }
        }
    }

    private fun checkManualReviewParam(params: MutableList<ManualReviewParam>) {
        params.forEach { item ->
            val value = item.value.toString()
            if (item.required && value.isBlank()) {
                throw ParamBlankException("requiredParam: ${item.key}  is Null")
            }
            if (value.isBlank()) {
                return@forEach
            }
            checkManualReviewParamOut(item.valueType, item, value)
        }
    }
}
