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

package com.tencent.devops.common.pipeline.pojo

import com.tencent.devops.common.api.util.EnvUtils
import com.tencent.devops.common.api.util.UUIDUtil
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ManualReviewAction
import com.tencent.devops.common.pipeline.option.StageControlOption
import com.tencent.devops.common.pipeline.pojo.element.atom.ManualReviewParam
import java.time.LocalDateTime

data class StagePauseCheck(
    var manualTrigger: Boolean? = false,
    var status: String? = null,
    var reviewDesc: String? = null,
    var reviewGroups: MutableList<StageReviewGroup>? = null, // 审核流配置
    var reviewParams: List<ManualReviewParam>? = null, // 审核变量
    var timeout: Int? = 24, // 等待审核的超时时间，默认24小时兜底
    var ruleIds: List<String>? = null, // 质量红线规则ID集合
    var checkTimes: Int? = null // 记录本次构建质量红线规则的检查次数
) {

    /**
     * 获取当前等待中的审核组
     */
    fun groupToReview(): StageReviewGroup? {
        reviewGroups?.forEach { group ->
            if (group.status.isNullOrBlank()) {
                return group
            }
        }
        return null
    }

    /**
     * 判断操作用户在不在当前审核人员名单中
     */
    fun reviewerContains(userId: String): Boolean {
        reviewGroups?.forEach { group ->
            if (group.status == null) {
                return group.reviewers.contains(userId)
            }
        }
        return false
    }

    /**
     * 审核通过当前等待中的审核组
     */
    fun reviewGroup(
        userId: String,
        action: ManualReviewAction,
        groupId: String? = null,
        params: List<ManualReviewParam>? = null,
        suggest: String? = null
    ): Boolean {
        val group = getReviewGroupById(groupId)
        if (group != null && group.status == null) {
            group.status = action.name
            group.operator = userId
            group.reviewTime = LocalDateTime.now().timestampmilli()
            group.suggest = suggest
            group.params = parseReviewParams(params)
            // #4531 如果没有剩下未审核的组则刷新为审核完成状态
            if (groupToReview() == null) {
                status = BuildStatus.REVIEW_PROCESSED.name
            } else if (action == ManualReviewAction.ABORT) {
                status = BuildStatus.REVIEW_ABORT.name
            }
            return true
        }
        return false
    }

    /**
     * 获取指定ID的审核组
     */
    fun getReviewGroupById(groupId: String?): StageReviewGroup? {
        // #4531 兼容旧的前端交互，如果是不带ID审核参数则默认返回第一个审核组（旧数据）
        if (groupId.isNullOrBlank()) return reviewGroups?.first()
        var group: StageReviewGroup? = null
        reviewGroups?.forEach { it ->
            if (it.id == groupId) {
                group = it
            }
        }
        return group
    }

    /**
     * 初始化状态并，填充审核组ID
     */
    fun fixReviewGroups(init: Boolean) {
        reviewGroups?.forEach { group ->
            if (group.id.isNullOrBlank()) group.id = UUIDUtil.generate()
            if (init) {
                group.status = null
                group.reviewTime = null
                group.operator = null
                group.params = null
            }
        }
    }

    /**
     * 处理审核参数 - 与默认值相同的变量不显示
     */
    fun parseReviewParams(params: List<ManualReviewParam>?): MutableList<ManualReviewParam>? {
        return try {
            // #4531 只显示有相比默认有修改的部分
            if (reviewParams.isNullOrEmpty() || params.isNullOrEmpty()) return null
            val originMap = reviewParams!!.associateBy { it.key }
            val diff = mutableListOf<ManualReviewParam>()
            params.forEach { param ->
                // 如果原变量里没有该key值则跳过
                if (!originMap.containsKey(param.key)) return@forEach
                if (originMap[param.key]?.value.toString() != param.value.toString()) {
                    diff.add(param)
                    originMap[param.key]?.value = param.value
                }
            }
            // 修改后的值传递到下一个审核组
            reviewParams?.forEach {
                it.value = originMap[it.key]?.value
            }
            diff
        } catch (ignore: Throwable) {
            null
        }
    }

    /**
     *  进入审核流程前完成所有审核人变量替换
     */
    fun parseReviewVariables(variables: Map<String, String>) {
        reviewGroups?.forEach { group ->
            if (group.status == null) {
                val reviewers = group.reviewers.joinToString(",")
                val realReviewers = EnvUtils.parseEnv(reviewers, variables)
                    .split(",").toList()
                group.reviewers = realReviewers
            }
        }
        reviewDesc = EnvUtils.parseEnv(reviewDesc, variables)
        reviewParams?.forEach {
            it.value = if (variables.containsKey(it.key)) {
                variables[it.key]
            } else {
                EnvUtils.parseEnv(it.value.toString(), variables)
            }
        }
    }

    /**
     *  重新恢复所有准入/准出状态
     */
    fun retryRefresh() {
        status = null
        reviewGroups?.forEach { group ->
            group.status = null
            group.params = null
            group.operator = null
            group.reviewTime = null
            group.suggest = null
        }
    }

    companion object {
        fun convertControlOption(stageControlOption: StageControlOption): StagePauseCheck {
            return StagePauseCheck(
                manualTrigger = stageControlOption.manualTrigger,
                status = if (stageControlOption.triggered == true) {
                    BuildStatus.REVIEW_PROCESSED.name
                } else null,
                reviewGroups = mutableListOf(StageReviewGroup(
                    id = UUIDUtil.generate(),
                    reviewers = stageControlOption.triggerUsers ?: listOf(),
                    status = if (stageControlOption.triggered == true) {
                        ManualReviewAction.PROCESS.name
                    } else null,
                    params = stageControlOption.reviewParams
                )),
                reviewDesc = stageControlOption.reviewDesc,
                reviewParams = stageControlOption.reviewParams,
                timeout = stageControlOption.timeout
            )
        }
    }
}
