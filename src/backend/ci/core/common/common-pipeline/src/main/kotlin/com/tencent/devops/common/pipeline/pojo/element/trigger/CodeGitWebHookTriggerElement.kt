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

package com.tencent.devops.common.pipeline.pojo.element.trigger

import com.tencent.devops.common.api.enums.RepositoryType
import com.tencent.devops.common.pipeline.enums.StartType
import com.tencent.devops.common.pipeline.pojo.element.trigger.enums.CodeEventType
import com.tencent.devops.common.pipeline.pojo.element.trigger.enums.PathFilterType
import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

@ApiModel("Git事件触发", description = CodeGitWebHookTriggerElement.classType)
data class CodeGitWebHookTriggerElement(
    @ApiModelProperty("任务名称", required = true)
    override val name: String = "Git变更触发",
    @ApiModelProperty("id", required = false)
    override var id: String? = null,
    @ApiModelProperty("状态", required = false)
    override var status: String? = null,
    @ApiModelProperty("仓库ID", required = true)
    val repositoryHashId: String?,
    @ApiModelProperty("branch", required = false)
    val branchName: String?,
    @ApiModelProperty("excludeBranch", required = false)
    val excludeBranchName: String?,
    @ApiModelProperty("路径过滤类型", required = true)
    val pathFilterType: PathFilterType? = PathFilterType.NamePrefixFilter,
    @ApiModelProperty("includePaths", required = false)
    val includePaths: String?,
    @ApiModelProperty("excludePaths", required = false)
    val excludePaths: String?,
    @ApiModelProperty("excludeUsers", required = false)
    val excludeUsers: List<String>?,
    @ApiModelProperty("eventType", required = false)
    val eventType: CodeEventType?,
    @ApiModelProperty("block", required = false)
    val block: Boolean?,
    @ApiModelProperty("新版的git原子的类型")
    val repositoryType: RepositoryType? = null,
    @ApiModelProperty("新版的git代码库名")
    val repositoryName: String? = null,
    @ApiModelProperty("tagName", required = false)
    val tagName: String? = null,
    @ApiModelProperty("excludeTagName", required = false)
    val excludeTagName: String? = null,
    @ApiModelProperty("excludeSourceBranchName", required = false)
    val excludeSourceBranchName: String? = null,
    @ApiModelProperty("includeSourceBranchName", required = false)
    val includeSourceBranchName: String? = null,
    @ApiModelProperty("webhook队列", required = false)
    val webhookQueue: Boolean? = false,
    @ApiModelProperty("code review 状态", required = false)
    val includeCrState: List<String>? = null
) : WebHookTriggerElement(name, id, status) {
    companion object {
        const val classType = "codeGitWebHookTrigger"
    }

    override fun getClassType() = classType

    override fun findFirstTaskIdByStartType(startType: StartType): String {
        return if (startType.name == StartType.WEB_HOOK.name) {
            this.id!!
        } else {
            super.findFirstTaskIdByStartType(startType)
        }
    }
}
