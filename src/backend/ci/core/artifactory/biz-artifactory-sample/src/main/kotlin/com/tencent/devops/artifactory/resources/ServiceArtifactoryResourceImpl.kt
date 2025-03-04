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

package com.tencent.devops.artifactory.resources

import com.tencent.devops.artifactory.api.service.ServiceArtifactoryResource
import com.tencent.devops.artifactory.pojo.ArtifactoryCreateInfo
import com.tencent.devops.artifactory.pojo.Count
import com.tencent.devops.artifactory.pojo.CustomFileSearchCondition
import com.tencent.devops.artifactory.pojo.DockerUser
import com.tencent.devops.artifactory.pojo.FileDetail
import com.tencent.devops.artifactory.pojo.FileInfo
import com.tencent.devops.artifactory.pojo.FileInfoPage
import com.tencent.devops.artifactory.pojo.Property
import com.tencent.devops.artifactory.pojo.SearchProps
import com.tencent.devops.artifactory.pojo.Url
import com.tencent.devops.artifactory.pojo.enums.ArtifactoryType
import com.tencent.devops.artifactory.pojo.enums.FileChannelTypeEnum
import com.tencent.devops.artifactory.service.ArchiveFileService
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.pojo.Result
import com.tencent.devops.common.web.RestResource
import org.springframework.beans.factory.annotation.Autowired

@RestResource
@Suppress("ALL", "UNUSED")
class ServiceArtifactoryResourceImpl @Autowired constructor(
    private val archiveFileService: ArchiveFileService
) : ServiceArtifactoryResource {

    override fun check(projectId: String, artifactoryType: ArtifactoryType, path: String): Result<Boolean> {
        val fileDetail =
            archiveFileService.show(userId = "", projectId = projectId, artifactoryType = artifactoryType, path = path)
        return Result(fileDetail.name.isNotBlank())
    }

    override fun acrossProjectCopy(
        projectId: String,
        artifactoryType: ArtifactoryType,
        path: String,
        targetProjectId: String,
        targetPath: String
    ): Result<Count> {
        TODO("not implemented")
    }

    override fun properties(projectId: String, artifactoryType: ArtifactoryType, path: String): Result<List<Property>> {
        TODO("not implemented")
    }

    override fun externalUrl(
        projectId: String,
        artifactoryType: ArtifactoryType,
        userId: String,
        path: String,
        ttl: Int,
        directed: Boolean?
    ): Result<Url> {
        TODO("not implemented")
    }

    override fun downloadUrlForOpenApi(
        userId: String,
        projectId: String,
        artifactoryType: ArtifactoryType,
        path: String
    ): Result<Url> {
        val urls = archiveFileService.getFileDownloadUrls(
            fileChannelType = FileChannelTypeEnum.WEB_DOWNLOAD, filePath = path, artifactoryType = artifactoryType
        )
        return Result(Url(urls.fileUrlList[0], urls.fileUrlList[0]))
    }

    override fun downloadUrl(
        projectId: String,
        artifactoryType: ArtifactoryType,
        userId: String,
        path: String,
        ttl: Int,
        directed: Boolean?
    ): Result<Url> {
        TODO("not implemented")
    }

    override fun show(projectId: String, artifactoryType: ArtifactoryType, path: String): Result<FileDetail> {
        TODO("not implemented")
    }

    override fun search(
        userId: String?,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        searchProps: List<Property>
    ): Result<FileInfoPage<FileInfo>> {
        TODO("not implemented")
    }

    override fun searchFile(
        projectId: String,
        pipelineId: String,
        buildId: String,
        regexPath: String,
        customized: Boolean,
        page: Int?,
        pageSize: Int?
    ): Result<FileInfoPage<FileInfo>> {
        TODO("not implemented")
    }

    override fun searchFileAndPropertyByAnd(
        projectId: String,
        page: Int?,
        pageSize: Int?,
        searchProps: List<Property>
    ): Result<FileInfoPage<FileInfo>> {
        TODO("not implemented")
    }

    override fun searchFileAndPropertyByOr(
        projectId: String,
        page: Int?,
        pageSize: Int?,
        searchProps: List<Property>
    ): Result<FileInfoPage<FileInfo>> {
        TODO("not implemented")
    }

    override fun createDockerUser(projectId: String): Result<DockerUser> {
        TODO("not implemented")
    }

    override fun setProperties(
        projectId: String,
        imageName: String,
        tag: String,
        properties: Map<String, String>
    ): Result<Boolean> {
        TODO("not implemented")
    }

    override fun searchCustomFiles(projectId: String, condition: CustomFileSearchCondition): Result<List<String>> {
        TODO("not implemented")
    }

    override fun getJforgInfoByteewTime(
        startTime: Long,
        endTime: Long,
        page: Int,
        pageSize: Int
    ): Result<List<FileInfo>> {
        TODO("not implemented")
    }

    override fun createArtifactoryInfo(
        buildId: String,
        pipelineId: String,
        projectId: String,
        buildNum: Int,
        fileInfo: FileInfo,
        dataFrom: Int
    ): Result<Long> {
        TODO("not implemented")
    }

    override fun batchCreateArtifactoryInfo(infoList: List<ArtifactoryCreateInfo>): Result<Int> {
        TODO("not implemented")
    }

    override fun getReportRootUrl(
        projectId: String,
        pipelineId: String,
        buildId: String,
        taskId: String
    ): Result<String> {
        return Result(archiveFileService.getReportRootUrl(projectId, pipelineId, buildId, taskId))
    }

    override fun searchFile(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        searchProps: SearchProps
    ): Result<Page<FileInfo>> {
        val fileList = archiveFileService.searchFileList(
            userId = userId,
            projectId = projectId,
            page = page,
            pageSize = pageSize,
            searchProps = searchProps
        )
        return Result(fileList)
    }
}
