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

package com.tencent.devops.quality.service.v2

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.util.EnvUtils
import com.tencent.devops.common.api.util.HashUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.notify.enums.NotifyType
import com.tencent.devops.common.quality.pojo.QualityRuleInterceptRecord
import com.tencent.devops.common.quality.pojo.RuleCheckResult
import com.tencent.devops.common.quality.pojo.RuleCheckSingleResult
import com.tencent.devops.common.quality.pojo.enums.RuleInterceptResult
import com.tencent.devops.notify.PIPELINE_QUALITY_AUDIT_NOTIFY_TEMPLATE
import com.tencent.devops.notify.PIPELINE_QUALITY_END_NOTIFY_TEMPLATE
import com.tencent.devops.notify.api.service.ServiceNotifyMessageTemplateResource
import com.tencent.devops.notify.pojo.SendNotifyMessageTemplateRequest
import com.tencent.devops.plugin.api.ServiceCodeccElementResource
import com.tencent.devops.plugin.codecc.CodeccUtils
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.process.utils.PIPELINE_START_USER_ID
import com.tencent.devops.project.api.service.ServiceProjectResource
import com.tencent.devops.quality.api.v2.pojo.QualityHisMetadata
import com.tencent.devops.quality.api.v2.pojo.QualityIndicator
import com.tencent.devops.quality.api.v2.pojo.QualityRule
import com.tencent.devops.quality.api.v2.pojo.enums.QualityDataType
import com.tencent.devops.quality.api.v2.pojo.request.BuildCheckParams
import com.tencent.devops.quality.api.v2.pojo.response.AtomRuleResponse
import com.tencent.devops.quality.api.v2.pojo.response.QualityRuleMatchTask
import com.tencent.devops.quality.api.v3.pojo.request.BuildCheckParamsV3
import com.tencent.devops.quality.bean.QualityUrlBean
import com.tencent.devops.quality.constant.DEFAULT_CODECC_URL
import com.tencent.devops.quality.constant.codeccToolUrlPathMap
import com.tencent.devops.quality.pojo.RefreshType
import com.tencent.devops.quality.pojo.enum.RuleOperation
import com.tencent.devops.quality.service.QualityNotifyGroupService
import com.tencent.devops.quality.util.ThresholdOperationUtil
import org.apache.commons.lang3.math.NumberUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.concurrent.Executors

@Service
@Suppress(
    "TooManyFunctions",
    "LongParameterList",
    "NestedBlockDepth",
    "ReturnCount",
    "MagicNumber",
    "ComplexMethod",
    "LongMethod"
)
class QualityRuleCheckService @Autowired constructor(
    private val ruleService: QualityRuleService,
    private val qualityHisMetadataService: QualityHisMetadataService,
    private val qualityNotifyGroupService: QualityNotifyGroupService,
    private val countService: QualityCountService,
    private val historyService: QualityHistoryService,
    private val controlPointService: QualityControlPointService,
    private val client: Client,
    private val objectMapper: ObjectMapper,
    private val qualityCacheService: QualityCacheService,
    private val qualityRuleBuildHisService: QualityRuleBuildHisService,
    private val qualityUrlBean: QualityUrlBean
) {
    private val executors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

    fun userGetMatchRuleList(projectId: String, pipelineId: String): List<QualityRuleMatchTask> {
        // 取出项目下包含该流水线的所有红线，再按控制点分组
        val filterRuleList = ruleService.getProjectRuleList(projectId, pipelineId, null)
        return ruleService.listMatchTask(filterRuleList)
    }

    fun userGetMatchTemplateList(projectId: String, templateId: String?): List<QualityRuleMatchTask> {
        val ruleList = ruleService.getProjectRuleList(projectId, null, templateId)
        return ruleService.listMatchTask(ruleList)
    }

    fun getMatchRuleListByCache(projectId: String, pipelineId: String): List<QualityRuleMatchTask> {
        val cacheData = qualityCacheService.getCacheRuleListByPipelineId(projectId, pipelineId)
        if (cacheData != null) {
            return cacheData
        }
        logger.info("userGetMatchRuleList redis is empty, $projectId| $pipelineId")
        // 取出项目下包含该流水线的所有红线，再按控制点分组
        val qualityTasks = userGetMatchRuleList(projectId, pipelineId)
        qualityCacheService.refreshCache(
            projectId = projectId,
            pipelineId = pipelineId,
            templateId = null,
            ruleTasks = qualityTasks,
            type = RefreshType.GET
        )
        return qualityTasks
    }

    fun getMatchTemplateListByCache(projectId: String, templateId: String?): List<QualityRuleMatchTask> {
        if (templateId.isNullOrBlank()) return listOf()
        val cacheData = qualityCacheService.getCacheRuleListByTemplateId(projectId, templateId)
        if (cacheData != null) {
            return cacheData
        }
        logger.info("userGetMatchTemplateList redis is empty, $projectId| $templateId")
        val qualityTasks = userGetMatchTemplateList(projectId, templateId)
        qualityCacheService.refreshCache(
            projectId = projectId,
            pipelineId = null,
            templateId = templateId,
            ruleTasks = qualityTasks,
            type = RefreshType.GET
        )
        return qualityTasks
    }

    fun userListAtomRule(
        projectId: String,
        pipelineId: String,
        atomCode: String,
        atomVersion: String
    ): AtomRuleResponse {
        val filterRuleList = ruleService.getProjectRuleList(
            projectId = projectId,
            pipelineId = pipelineId,
            templateId = null).filter { it.controlPoint?.name == atomCode }
        val ruleList = ruleService.listMatchTask(filterRuleList)
        val isControlPoint = controlPointService.isControlPoint(atomCode, atomVersion, projectId)
        return AtomRuleResponse(isControlPoint, ruleList)
    }

    fun userListTemplateAtomRule(
        projectId: String,
        templateId: String,
        atomCode: String,
        atomVersion: String
    ): AtomRuleResponse {
        val filterRuleList = ruleService.getProjectRuleList(
            projectId = projectId,
            pipelineId = null,
            templateId = templateId).filter { it.controlPoint?.name == atomCode }
        val ruleList = ruleService.listMatchTask(filterRuleList)
        val isControlPoint = controlPointService.isControlPoint(atomCode, atomVersion, projectId)
        return AtomRuleResponse(isControlPoint, ruleList)
    }

    fun check(buildCheckParams: BuildCheckParams): RuleCheckResult {
        // 遍历项目下所有拦截规则
        val ruleList = ruleService.serviceListRuleByPosition(
            buildCheckParams.projectId,
            buildCheckParams.position
        )

        return doCheckRules(buildCheckParams, ruleList)
    }

    fun checkBuildHis(buildCheckParams: BuildCheckParamsV3): RuleCheckResult {
        val ruleBuildId = buildCheckParams.ruleBuildIds.map {
            HashUtil.decodeIdToLong(it)
        }

        // 遍历项目下所有拦截规则
        val ruleList = qualityRuleBuildHisService.list(ruleBuildId)

        // 更新build id
        qualityRuleBuildHisService.updateBuildId(ruleBuildId, buildCheckParams.buildId)

        val params = BuildCheckParams(
            buildCheckParams.projectId,
            buildCheckParams.pipelineId,
            buildCheckParams.buildId,
            "",
            buildCheckParams.interceptName ?: "",
            System.currentTimeMillis(),
            "",
            "",
            buildCheckParams.templateId,
            buildCheckParams.runtimeVariable
        )
        return doCheckRules(buildCheckParams = params, ruleList = ruleList)
    }

    private fun doCheckRules(buildCheckParams: BuildCheckParams, ruleList: List<QualityRule>): RuleCheckResult {
        with(buildCheckParams) {
            val filterRuleList = ruleList.filter { rule ->
                logger.info("validate whether to check rule(${rule.name}) with gatewayId(${rule.gatewayId})")
                if (!buildCheckParams.taskId.isBlank() && rule.controlPoint.name != buildCheckParams.taskId) {
                    return@filter false
                }
                val gatewayId = rule.gatewayId ?: ""
                if (!buildCheckParams.interceptTaskName.toLowerCase().contains(gatewayId.toLowerCase())) {
                    return@filter false
                }

                val containsInPipeline = rule.range.contains(pipelineId)
                val containsInTemplate = rule.templateRange.contains(buildCheckParams.templateId)
                return@filter (containsInPipeline || containsInTemplate)
            }

            val resultPair = doCheck(projectId, pipelineId, buildId, filterRuleList, runtimeVariable)
            val resultList = resultPair.first
            val ruleInterceptList = resultPair.second

            // 异步后续的处理
            executors.execute { checkPostHandle(buildCheckParams, ruleInterceptList, resultList) }

            // 记录结果
            val checkTimes = recordHistory(buildCheckParams, ruleInterceptList)

            return genResult(projectId, pipelineId, buildId, checkTimes, resultList, ruleInterceptList)
        }
    }

    private fun doCheck(
        projectId: String,
        pipelineId: String,
        buildId: String,
        filterRuleList: List<QualityRule>,
        runtimeVariable: Map<String, String>?
    ): Pair<List<RuleCheckSingleResult>, List<Triple<QualityRule, Boolean, List<QualityRuleInterceptRecord>>>> {
        val resultList = mutableListOf<RuleCheckSingleResult>()
        val ruleInterceptList = mutableListOf<Triple<QualityRule, Boolean, List<QualityRuleInterceptRecord>>>()

        // start to check
        val metadataList = qualityHisMetadataService.serviceGetHisMetadata(buildId)
        filterRuleList.forEach { rule ->
            logger.info("start to check rule(${rule.name})")

            val result = checkIndicator(
                rule.controlPoint.name, rule.indicators, metadataList
            )
            val interceptRecordList = result.second
            val interceptResult = result.first
            val params = mapOf("projectId" to projectId,
                "pipelineId" to pipelineId,
                "buildId" to buildId,
                CodeccUtils.BK_CI_CODECC_TASK_ID to
                    (runtimeVariable?.get(CodeccUtils.BK_CI_CODECC_TASK_ID) ?: "")
            )

            resultList.add(getRuleCheckSingleResult(rule.name, interceptRecordList, params))
            ruleInterceptList.add(Triple(rule, interceptResult, interceptRecordList))
        }

        return Pair(resultList, ruleInterceptList)
    }

    private fun genResult(
        projectId: String,
        pipelineId: String,
        buildId: String,
        checkTimes: Int,
        resultList: List<RuleCheckSingleResult>,
        ruleInterceptList: List<Triple<QualityRule, Boolean, List<QualityRuleInterceptRecord>>>
    ): RuleCheckResult {
        // generate result
        val failRule = ruleInterceptList.filter { !it.second }.map { it.first }
        val allPass = failRule.isEmpty()
        val allEnd = allPass || (!allPass && !failRule.any { it.operation == RuleOperation.AUDIT })
        val auditTimeOutMinutes = if (!allPass) {
            Collections.min(failRule.map { it.auditTimeoutMinutes ?: DEFAULT_TIMEOUT_MINUTES })
        } else DEFAULT_TIMEOUT_MINUTES
        logger.info("check result allPass($allPass) allEnd($allEnd) auditTimeoutMinutes($auditTimeOutMinutes)")
        logger.info("end check pipeline build: $projectId, $pipelineId, $buildId")
        return RuleCheckResult(allPass, allEnd, auditTimeOutMinutes * 60L, checkTimes, resultList)
    }

    private fun checkPostHandle(
        buildCheckParams: BuildCheckParams,
        result: List<Triple<QualityRule, Boolean, List<QualityRuleInterceptRecord>>>,
        resultList: List<RuleCheckSingleResult>
    ) {
        result.forEach {
            val rule = it.first
            val ruleId = HashUtil.decodeIdToLong(rule.hashId)
            val interceptResult = it.second
            val interceptRecordList = it.third

            with(buildCheckParams) {
                ruleService.plusExecuteCount(ruleId)

                if (!interceptResult) {
                    ruleService.plusInterceptTimes(ruleId)

                    try {
                        if (rule.opList != null) {
                            logger.info("do op list action: $buildId, $rule")
                            rule.opList!!.forEach { ruleOp ->
                                doRuleOperation(buildCheckParams, interceptRecordList, resultList, ruleOp)
                            }
                        } else {
                            logger.info("op list is empty for rule and build: $buildId, $rule")
                            doRuleOperation(this, interceptRecordList, resultList, QualityRule.RuleOp(
                                operation = rule.operation,
                                notifyTypeList = rule.notifyTypeList,
                                notifyGroupList = rule.notifyGroupList,
                                notifyUserList = rule.notifyUserList,
                                auditUserList = rule.auditUserList,
                                auditTimeoutMinutes = rule.auditTimeoutMinutes
                            ))
                        }
                    } catch (ignored: Throwable) {
                        logger.error("send notification fail", ignored)
                    }
                }
                countService.countIntercept(projectId, pipelineId, ruleId, interceptResult)
            }
        }
    }

    private fun doRuleOperation(
        buildCheckParams: BuildCheckParams,
        interceptRecordList: List<QualityRuleInterceptRecord>,
        resultList: List<RuleCheckSingleResult>,
        ruleOp: QualityRule.RuleOp
    ) {
        with(buildCheckParams) {
            val createTime = LocalDateTime.now()
            if (ruleOp.operation == RuleOperation.END) {
                sendEndNotification(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    buildId = buildId,
                    buildNo = buildNo,
                    createTime = createTime,
                    interceptRecordList = interceptRecordList,
                    endNotifyTypeList = ruleOp.notifyTypeList ?: listOf(),
                    endNotifyGroupList = ruleOp.notifyGroupList ?: listOf(),
                    endNotifyUserList = (ruleOp.notifyUserList ?: listOf()).map { user ->
                        EnvUtils.parseEnv(user, runtimeVariable ?: mapOf())
                    },
                    runtimeVariable = buildCheckParams.runtimeVariable
                )
            } else {
                val startUser = runtimeVariable?.get(PIPELINE_START_USER_ID) ?: ""
                sendAuditNotification(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    buildId = buildId,
                    buildNo = buildNo,
                    createTime = createTime,
                    resultList = resultList,
                    auditNotifyUserList = (ruleOp.auditUserList
                        ?: listOf()).toSet().plus(startUser).map { user ->
                        EnvUtils.parseEnv(user, runtimeVariable ?: mapOf())
                    },
                    runtimeVariable = buildCheckParams.runtimeVariable
                )
            }
        }
    }

    private fun checkIndicator(
        controlPointName: String,
        indicators: List<QualityIndicator>,
        metadataList: List<QualityHisMetadata>
    ): Pair<Boolean, MutableList<QualityRuleInterceptRecord>> {
        var allCheckResult = true
        val interceptList = mutableListOf<QualityRuleInterceptRecord>()
        val metadataMap = metadataList.map { it.enName to it }.toMap()
        // 遍历每个指标
        indicators.forEach { indicator ->
            val thresholdType = indicator.thresholdType
            var checkResult = true

            // 脚本原子的指标特殊处理：取指标英文名 = 基础数据名
            val filterMetadataList = if (indicator.isScriptElementIndicator()) {
                metadataList
                    .filter { indicator.enName == it.enName }
                    .filter { it.elementType in QualityIndicator.SCRIPT_ELEMENT }
            } else {
                indicator.metadataList.map { metadataMap[it.enName] }
            }

            // 遍历所有基础数据
            var elementDetail = ""
            val result: String? = when (thresholdType) {
                // int类型把所有基础数据累加
                QualityDataType.INT -> {
                    var result: Int? = null
                    for (it in filterMetadataList) {
                        // -1表示直接失败
                        if (DETAIL_NOT_RUN_VALUE == it?.value) {
                            result = null
                            break
                        }

                        if (it?.value != null && NumberUtils.isCreatable(it.value)) {
                            val value = it.value.toInt()
                            result = (result ?: 0) + value
                            // 记录”查看详情“里面跳转的基础数据, 记录第一个
                            if (value >= 0 && elementDetail.isBlank()) elementDetail = it.detail
                        }
                    }
                    if (!ThresholdOperationUtil.valid(result?.toString(), indicator.threshold, indicator.operation)) {
                        checkResult = false
                        allCheckResult = false
                    }
                    result?.toString()
                }
                // float类型把所有基础数据累加
                QualityDataType.FLOAT -> {
                    var result: BigDecimal? = null
                    for (it in filterMetadataList) {

                        if (it?.value != null && NumberUtils.isCreatable(it.value)) {
                            val value = BigDecimal(it.value)

                            // -1表示直接失败
                            if (DETAIL_NOT_RUN_FLOAT_VALUE.compareTo(value) == 0) {
                                result = null
                                break
                            }

                            result = result?.plus(BigDecimal(it.value)) ?: BigDecimal(it.value)
                            // 记录”查看详情“里面跳转的基础数据
                            if (value >= BigDecimal(0) && elementDetail.isBlank()) elementDetail = it.detail
                        }
                    }
                    if (!ThresholdOperationUtil.validDecimal(actualValue = result,
                            boundaryValue = BigDecimal(indicator.threshold),
                            operation = indicator.operation)) {
                        checkResult = false
                        allCheckResult = false
                    }
                    result?.toString()
                }
                // 布尔类型把所有基础数据求与
                QualityDataType.BOOLEAN -> {
                    logger.info("is boolean...")
                    var result: Boolean? = null
                    val threshold = indicator.threshold.toBoolean()
                    logger.info("boolean threshold: $threshold")
                    for (it in filterMetadataList) {
                        logger.info("each value: ${it?.value}")
                        if (it?.value != null &&
                            (it.value.toLowerCase() == "true" || it.value.toLowerCase() == "false")) {
                            val value = it.value.toBoolean()
                            logger.info("each convert value: $value")
                            if (value != threshold) {
                                checkResult = false
                                allCheckResult = false
                                result = value
                                // 记录”查看详情“里面跳转的基础数据
                                elementDetail = it.detail
                                break
                            } else {
                                // 全通过了，也要有值
                                result = threshold
                            }
                        }
                    }

                    // 全为null，不通过
                    if (!ThresholdOperationUtil.validBoolean(result?.toString()
                            ?: "", indicator.threshold, indicator.operation)) {
                        checkResult = false
                        allCheckResult = false
                    }
                    result?.toString()
                }
                else -> {
                    null
                }
            }
            with(indicator) {
                interceptList.add(
                    QualityRuleInterceptRecord(
                        indicatorId = hashId, indicatorName = cnName, indicatorType = elementType,
                        controlPoint = controlPointName, operation = operation, value = threshold, actualValue = result,
                        pass = checkResult, detail = elementDetail, logPrompt = logPrompt)
                )
            }
        }
        return Pair(allCheckResult, interceptList)
    }

    /**
     * 获取单个拦截成功信息
     */
    private fun getRuleCheckSingleResult(
        ruleName: String,
        interceptRecordList: List<QualityRuleInterceptRecord>,
        params: Map<String, String>
    ): RuleCheckSingleResult {
        val messageList = interceptRecordList.map {
            val thresholdOperationName = ThresholdOperationUtil.getOperationName(it.operation)

            val sb = StringBuilder()
            if (it.pass) {
                sb.append("已通过：")
            } else {
                sb.append("已拦截：")
            }
            val nullMsg = if (it.actualValue == null) "你可能并未添加工具或打开相应规则。" else ""
            val detailMsg = getDetailMsg(it, params)
            Pair(
                sb.append("${it.indicatorName}当前值(${it.actualValue})，期望$thresholdOperationName${it.value}。 $nullMsg")
                    .toString(),
                detailMsg
            )
        }
        return RuleCheckSingleResult(ruleName, messageList)
    }

    private fun getDetailMsg(record: QualityRuleInterceptRecord, params: Map<String, String>): String {
        // codecc跳到独立入口页面
        return if (CodeccUtils.isCodeccAtom(record.indicatorType)) {
            val projectId = params["projectId"] ?: ""
            val pipelineId = params["pipelineId"] ?: ""
            val buildId = params["buildId"] ?: ""
            val taskId = getTaskId(projectId, pipelineId, params)
            if (taskId.isBlank()) {
                logger.warn("taskId is null or blank for project($projectId) pipeline($pipelineId)")
                return ""
            }
            if (record.detail.isNullOrBlank()) { // #4796 日志展示的链接去掉域名
                "<a target='_blank' href='/console/codecc/$projectId/task/$taskId/detail'>查看详情</a>"
            } else {
                val detailUrl = codeccToolUrlPathMap[record.detail!!] ?: DEFAULT_CODECC_URL
                val fillDetailUrl = detailUrl.replace("##projectId##", projectId)
                    .replace("##taskId##", taskId.toString())
                    .replace("##buildId##", buildId)
                    .replace("##detail##", record.detail!!)
                "<a target='_blank' href='$fillDetailUrl'>查看详情</a>"
            }
        } else {
            record.logPrompt ?: ""
        }
    }

    private fun getTaskId(projectId: String, pipelineId: String, params: Map<String, String>): String {
        val paramTaskId = params[CodeccUtils.BK_CI_CODECC_TASK_ID]

        return if (paramTaskId.isNullOrBlank()) {
            try {
                client.get(ServiceCodeccElementResource::class).get(projectId, pipelineId).data?.taskId ?: ""
            } catch (e: Exception) {
                logger.warn("fail to get codecc task id: ${e.message}")
                ""
            }
        } else {
            paramTaskId
        }
    }

    /**
     * 记录拦截历史
     */
    private fun recordHistory(
        buildCheckParams: BuildCheckParams,
        result: List<Triple<QualityRule, Boolean, List<QualityRuleInterceptRecord>>>
    ): Int {
        val time = LocalDateTime.now()

        return with(buildCheckParams) {
            result.map {
                val rule = it.first
                val ruleId = HashUtil.decodeIdToLong(rule.hashId)
                val pass = it.second
                val interceptRecordList = it.third

                val interceptList = objectMapper.writeValueAsString(interceptRecordList)
                if (pass) {
                    historyService.serviceCreate(projectId = projectId,
                        ruleId = ruleId,
                        pipelineId = pipelineId,
                        buildId = buildId,
                        result = RuleInterceptResult.PASS.name,
                        interceptList = interceptList,
                        createTime = time,
                        updateTime = time)
                } else {
                    historyService.serviceCreate(projectId = projectId,
                        ruleId = ruleId,
                        pipelineId = pipelineId,
                        buildId = buildId,
                        result = RuleInterceptResult.FAIL.name,
                        interceptList = interceptList,
                        createTime = time,
                        updateTime = time)
                }
            }.firstOrNull() ?: 1
        }
    }

    private fun sendAuditNotification(
        projectId: String,
        pipelineId: String,
        buildId: String,
        buildNo: String,
        createTime: LocalDateTime,
        resultList: List<RuleCheckSingleResult>,
        auditNotifyUserList: List<String>,
        runtimeVariable: Map<String, String>?
    ) {
        val projectName = getProjectName(projectId)
        val pipelineName = getPipelineName(projectId, pipelineId)
        val url = qualityUrlBean.genBuildDetailUrl(projectId, pipelineId, buildId, runtimeVariable)
        val time = createTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm:ss"))

        // 获取通知用户集合
        val notifyUserSet = auditNotifyUserList.toMutableSet()

        // 获取拦截列表
        // val interceptList = getInterceptList(interceptRecordList)

        val messageResult = StringBuilder()
        val emailResult = StringBuilder()
        resultList.forEach { r ->
            messageResult.append("拦截规则：${r.ruleName}\n")
            messageResult.append("拦截指标：\n")
            emailResult.append("拦截规则：${r.ruleName}<br>")
            emailResult.append("拦截指标：<br>")
            r.messagePairs.forEach {
                messageResult.append(it.first + "\n")
                emailResult.append(it.first + "<br>")
            }
            emailResult.append("<br>")
        }

        // 推送消息
        val sendNotifyMessageTemplateRequest = SendNotifyMessageTemplateRequest(
            templateCode = PIPELINE_QUALITY_AUDIT_NOTIFY_TEMPLATE,
            receivers = notifyUserSet,
            cc = notifyUserSet,
            titleParams = mapOf(
                "projectName" to projectName,
                "pipelineName" to pipelineName,
                "buildNo" to buildNo
            ),
            bodyParams = mapOf(
                "title" to "【质量红线拦截通知】你有一个流水线被拦截",
                "projectName" to projectName,
                "pipelineName" to pipelineName,
                "buildNo" to buildNo,
                "time" to time,
                "result" to messageResult.toString(),
                "emailResult" to emailResult.toString(),
                "url" to url
            )
        )
        val sendNotifyResult = client.get(ServiceNotifyMessageTemplateResource::class)
            .sendNotifyMessageByTemplate(sendNotifyMessageTemplateRequest)
        logger.info("[$buildNo]|sendAuditNotification|QualityRuleCheckService|result=$sendNotifyResult")
    }

    /**
     * 发送终止或者审核通知
     */
    private fun sendEndNotification(
        projectId: String,
        pipelineId: String,
        buildId: String,
        buildNo: String,
        createTime: LocalDateTime,
        interceptRecordList: List<QualityRuleInterceptRecord>,
        endNotifyTypeList: List<NotifyType>,
        endNotifyGroupList: List<String>,
        endNotifyUserList: List<String>,
        runtimeVariable: Map<String, String>?
    ) {
        val projectName = getProjectName(projectId)
        val pipelineName = getPipelineName(projectId, pipelineId)
        val url = qualityUrlBean.genBuildDetailUrl(projectId, pipelineId, buildId, runtimeVariable)
        val time = createTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        // 获取通知用户集合
        val notifyUserSet = mutableSetOf<String>()

        val groupUsers = qualityNotifyGroupService.serviceGetUsers(endNotifyGroupList)
        notifyUserSet.addAll(groupUsers.innerUsers)
        notifyUserSet.addAll(endNotifyUserList)

        // 获取拦截列表
        val interceptList = getInterceptList(interceptRecordList)

        val sendNotifyMessageTemplateRequest = SendNotifyMessageTemplateRequest(
            templateCode = PIPELINE_QUALITY_END_NOTIFY_TEMPLATE,
            receivers = notifyUserSet,
            notifyType = endNotifyTypeList.map { it.name }.toMutableSet(),
            titleParams = mapOf(),
            bodyParams = mapOf(
                "title" to "【质量红线拦截通知】你有一个流水线被拦截",
                "projectName" to projectName,
                "pipelineName" to pipelineName,
                "buildNo" to buildNo,
                "time" to time,
                "thresholdListString" to interceptList.joinToString("；"),
                "url" to url
            )
        )
        val sendNotifyResult = client.get(ServiceNotifyMessageTemplateResource::class)
            .sendNotifyMessageByTemplate(sendNotifyMessageTemplateRequest)
        logger.info("[$buildId]|sendAuditNotification|result=$sendNotifyResult")
    }

    private fun getInterceptList(interceptRecordList: List<QualityRuleInterceptRecord>): List<String> {
        return interceptRecordList.filter { !it.pass }.map {
            val oppositeOperationName = ThresholdOperationUtil.getOperationOppositeName(it.operation)
            "${it.indicatorName}当前值(${it.actualValue}) $oppositeOperationName 期望值(${it.value})"
        }
    }

    fun getAuditUserList(projectId: String, pipelineId: String, buildId: String, taskId: String): Set<String> {
        val interceptList = historyService.serviceListByBuildIdAndResult(projectId = projectId,
            pipelineId = pipelineId,
            buildId = buildId,
            result = RuleInterceptResult.FAIL.name)
        val ruleIdList = interceptList.map { it.ruleId }

        val auditUserList = mutableSetOf<String>()
        val ruleRecordList = ruleService.serviceListRuleByIds(projectId, ruleIdList.toSet())
        ruleRecordList.forEach {
            val auditNotifyUserList = it.auditUserList ?: listOf()
            if (it.controlPoint?.name == taskId) {
                auditUserList.addAll(auditNotifyUserList)
            }
        }

        return auditUserList
    }

    private fun getProjectName(projectId: String): String {
        val project = client.get(ServiceProjectResource::class).listByProjectCode(setOf(projectId)).data?.firstOrNull()
        return project?.projectName ?: throw OperationException("ProjectId: $projectId not exist")
    }

    private fun getPipelineName(projectId: String, pipelineId: String): String {
        val map = getPipelineIdToNameMap(projectId, setOf(pipelineId))
        return map[pipelineId] ?: ""
    }

    private fun getPipelineIdToNameMap(projectId: String, pipelineIdSet: Set<String>): Map<String, String> {
        return client.get(ServicePipelineResource::class).getPipelineNameByIds(projectId, pipelineIdSet).data!!
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QualityRuleCheckService::class.java)
        private const val DETAIL_NOT_RUN_VALUE = "-1"
        private const val DEFAULT_TIMEOUT_MINUTES = 15
        val DETAIL_NOT_RUN_FLOAT_VALUE = BigDecimal(-1)
    }
}
