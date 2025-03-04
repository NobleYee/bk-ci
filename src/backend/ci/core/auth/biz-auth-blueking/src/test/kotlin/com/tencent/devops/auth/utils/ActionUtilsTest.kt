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

package com.tencent.devops.auth.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ActionUtilsTest {

    @Test
    fun actionType() {
        val actionType = "pipeline"
        assertEquals(actionType, ActionUtils.actionType(actionType))
    }

    @Test
    fun actionType1() {
        val actionType = "pipeline_create"
        assertEquals("pipeline", ActionUtils.actionType(actionType))
    }

    @Test
    fun actionType2() {
        val actionType = "pipeline1_create"
        assertNotEquals("pipeline", ActionUtils.actionType(actionType))
    }

    @Test
    fun actionType3() {
        val actionType = "rule_create"
        assertEquals("rule", ActionUtils.actionType(actionType))
    }

    @Test
    fun actionType4() {
        val actionType = "experience_group_update"
        assertEquals("experience_group", ActionUtils.actionType(actionType))
    }

    @Test
    fun actionType5() {
        val actionType = "env_node_view"
        assertEquals("env_node", ActionUtils.actionType(actionType))
    }

    @Test
    fun actionType6() {
        val actionType = "experience_group_update"
        assertNotEquals("experience", ActionUtils.actionType(actionType))
    }
}
