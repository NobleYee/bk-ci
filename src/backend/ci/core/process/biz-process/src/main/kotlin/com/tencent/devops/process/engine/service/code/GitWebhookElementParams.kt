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

package com.tencent.devops.process.engine.service.code

import com.tencent.devops.common.api.util.EnvUtils
import com.tencent.devops.common.pipeline.pojo.element.trigger.CodeGitWebHookTriggerElement
import com.tencent.devops.common.pipeline.pojo.element.trigger.enums.CodeType
import com.tencent.devops.common.pipeline.utils.RepositoryConfigUtils
import com.tencent.devops.common.webhook.pojo.code.WebHookParams
import com.tencent.devops.process.pojo.code.ScmWebhookElementParams

class GitWebhookElementParams : ScmWebhookElementParams<CodeGitWebHookTriggerElement> {
    override fun getWebhookElementParams(
        element: CodeGitWebHookTriggerElement,
        variables: Map<String, String>
    ): WebHookParams? {
        val params = WebHookParams(
            repositoryConfig = RepositoryConfigUtils.replaceCodeProp(
                repositoryConfig = RepositoryConfigUtils.buildConfig(element),
                variables = variables
            )
        )
        params.excludeUsers = if (element.excludeUsers == null || element.excludeUsers!!.isEmpty()) {
            ""
        } else {
            EnvUtils.parseEnv(element.excludeUsers!!.joinToString(","), variables)
        }
        if (element.branchName == null) {
            return null
        }
        params.block = element.block ?: false
        params.branchName = EnvUtils.parseEnv(element.branchName!!, variables)
        params.eventType = element.eventType
        params.excludeBranchName = EnvUtils.parseEnv(element.excludeBranchName ?: "", variables)
        params.pathFilterType = element.pathFilterType
        params.includePaths = EnvUtils.parseEnv(element.includePaths ?: "", variables)
        params.excludePaths = EnvUtils.parseEnv(element.excludePaths ?: "", variables)
        params.codeType = CodeType.GIT
        params.tagName = EnvUtils.parseEnv(element.tagName ?: "", variables)
        params.excludeTagName = EnvUtils.parseEnv(element.excludeTagName ?: "", variables)
        params.excludeSourceBranchName = EnvUtils.parseEnv(element.excludeSourceBranchName ?: "", variables)
        params.includeSourceBranchName = EnvUtils.parseEnv(element.includeSourceBranchName ?: "", variables)
        params.webhookQueue = element.webhookQueue ?: false
        params.includeCrState = if (element.includeCrState.isNullOrEmpty()) {
            ""
        } else {
            element.includeCrState!!.joinToString(",")
        }
        return params
    }
}
