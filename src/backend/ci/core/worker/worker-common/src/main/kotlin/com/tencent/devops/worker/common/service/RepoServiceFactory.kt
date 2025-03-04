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

package com.tencent.devops.worker.common.service

import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

object RepoServiceFactory {

    private val repoServiceMap = ConcurrentHashMap<String, RepoService>()

    private var property: Properties? = null

    private const val REPO_CLASS_NAME = "repo.class.name"

    fun getInstance(): RepoService {
        // 从配置文件读取类名
        if (property == null) {
            val fileInputStream = RepoServiceFactory::class.java.getResourceAsStream("/.agent.properties")
            property = Properties()
            property!!.load(fileInputStream)
        }
        val className = property!![REPO_CLASS_NAME] as String
        var repoService = repoServiceMap[className]
        if (repoService == null) {
            // 通过反射生成对象并放入缓存中
            repoService = Class.forName(className).newInstance() as RepoService
            repoServiceMap[className] = repoService
        }
        return repoService
    }
}
