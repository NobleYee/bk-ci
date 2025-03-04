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

package com.tencent.devops.common.pipeline.container

import com.tencent.devops.common.pipeline.option.StageControlOption
import com.tencent.devops.common.pipeline.pojo.StagePauseCheck
import io.swagger.annotations.ApiModel
import io.swagger.annotations.ApiModelProperty

@ApiModel("流水线模型-阶段")
data class Stage(
    @ApiModelProperty("容器集合", required = true)
    val containers: List<Container> = listOf(),
    @ApiModelProperty("阶段ID", required = false)
    var id: String?,
    @ApiModelProperty("阶段名称", required = true)
    var name: String? = "",
    @ApiModelProperty("阶段标签", required = false, hidden = true)
    var tag: List<String?>? = null,
    @ApiModelProperty("阶段状态", required = false, hidden = true)
    var status: String? = null,
    @ApiModelProperty("阶段手动审核状态", required = false, hidden = true)
    var reviewStatus: String? = null,
    @ApiModelProperty("阶段启动时间", required = false, hidden = true)
    var startEpoch: Long? = null,
    @ApiModelProperty("容器运行时间", required = false, hidden = true)
    var elapsed: Long? = null,
    @ApiModelProperty("用户自定义环境变量", required = false)
    val customBuildEnv: Map<String, String>? = null,
    @ApiModelProperty("是否启用容器失败快速终止阶段", required = false)
    val fastKill: Boolean? = false,
    @ApiModelProperty("标识是否为FinallyStage，每个Model只能包含一个FinallyStage，并且处于最后位置", required = false)
    val finally: Boolean = false,
    @ApiModelProperty("当前Stage是否能重试", required = false)
    var canRetry: Boolean? = null,
    @ApiModelProperty("流程控制选项", required = true)
    var stageControlOption: StageControlOption? = null, // 为了兼容旧数据，所以定义为可空以及var
    @ApiModelProperty("当前Stage是否能重试", required = false)
    var checkIn: StagePauseCheck? = null, // stage准入配置
    @ApiModelProperty("当前Stage是否能重试", required = false)
    var checkOut: StagePauseCheck? = null // stage准出配置
) {
    /**
     * 兼容性逻辑 - 将原有的审核配置刷新到审核流中，并且补充审核组ID
     */
    fun refreshReviewOption(init: Boolean? = false) {
        if (checkIn != null) {
            checkIn?.fixReviewGroups(init == true)
            return
        }
        val originControlOption = stageControlOption ?: return
        if (originControlOption.manualTrigger == true) {
            checkIn = StagePauseCheck.convertControlOption(originControlOption)
        }
        // TODO 在下一次发布中增加抹除旧数据逻辑
//        stageControlOption?.triggerUsers = null
//        stageControlOption?.triggered = null
//        stageControlOption?.reviewParams = null
//        stageControlOption?.reviewDesc = null
    }
}
