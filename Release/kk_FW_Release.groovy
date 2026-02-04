pipeline {
    agent any
    
    environment {
        TOOLCHAIN = "/home/data0/jenkins/tools/gcc-arm-none-eabi-10.3-2021.10/bin"
        ARTIFACTORY = "http://10.10.192.15:8081/artifactory/kk/Release"
        ARTIFACTORY_CREDS = credentials('artifactory-jenkins')
        FWMS = "https://alpha.goertek.com:8883/api/firmware"
    }
    
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
        timeout(time: 2, unit: 'HOURS')
        timestamps()
    }

    stages {
        stage('ENV_INIT') {
            steps {
                script {
                    env.VER_NAME = params.APP_VERSION
                    env.VER_CODE = params.APP_VERSION.replaceAll('\\.', '')
                    echo "VerName: ${env.VER_NAME}"
                    echo "VerCode: ${env.VER_CODE}"
                    if (params['BRANCH-TARGET'] == "HIMA-144" || params['BRANCH-TARGET'] == "HIMA-118")  {
                        env.BRANCH = "HIMA"
                    } else {
                        env.BRANCH = params['BRANCH-TARGET']
                    }
                    echo "Branch: ${env.BRANCH}"
                    if (params['BRANCH-TARGET'] == "HIMA-118")  {
                        env.OUTPUT = "platform/z20k118mc/output"
                    } else {
                        env.OUTPUT = "platform/z20k144mc/output"
                    }
                    echo "Output: ${env.OUTPUT}"
                    env.PATH = "${env.TOOLCHAIN}:${env.PATH}"
                    sh 'which arm-none-eabi-gcc'
                }
            }
        }

        stage('CODE_SYNC') {
            options {
                retry(3)
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                checkout scmGit(
                    branches: [[name: "*/${env.BRANCH}"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/kk'
                    ]]
                )
            }
        }

        stage('BUILD') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                script {  
                    if (params['BRANCH-TARGET'] == "HIMA-118") {
                        dir('platform/z20k118mc') {
                            withEnv([
                                "APP_VERSION=${env.VER_NAME}"
                                ]) {     
                                ansiColor('xterm') {      // Enable ANSI color support
                                    sh 'arm-none-eabi-gcc --version'
                                    sh """
                                        git reset --hard
                                        git clean -fxd
                                        make clean
                                        make all -j\$(nproc) VERSION=\$APP_VERSION
                                    """
                                }
                            }
                        }
                    } else {
                        dir('platform/z20k144mc') {
                            withEnv([
                                "APP_VERSION=${env.VER_NAME}"
                                ]) {     
                                ansiColor('xterm') {      // Enable ANSI color support
                                    sh 'arm-none-eabi-gcc --version'
                                    sh """
                                        git reset --hard
                                        git clean -fxd
                                        make clean
                                        make all -j\$(nproc) VERSION=\$APP_VERSION
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
        
        stage('Firmware_Packaging') {
            steps {
                dir("${env.OUTPUT}") {
                    script {
                        // 在 Groovy 中拼接完整文件名
                        def zipFileName = "kk_${params['BRANCH-TARGET']}_${env.VER_NAME}.${new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date())}.zip"
                        
                        sh """
                            # 直接使用 Groovy 生成的文件名
                            ZIP_FILE="${zipFileName}"
                            cp ../../../kkdetect/kkdetect_api.h .
                            zip -r "\${ZIP_FILE}" kkdetect_api.h kkdetect.a z20k1*mc_flash.elf z20k1*mc_flash.hex || { echo "Zip failed"; exit 1; }
                            md5sum "\${ZIP_FILE}" > "\${ZIP_FILE}.md5"
                        """
                        
                        archiveArtifacts artifacts: "${zipFileName}", fingerprint: true
                        archiveArtifacts artifacts: "${zipFileName}.md5", fingerprint: true
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                dir("${env.OUTPUT}") {
                    sh """
                        ZIP_FILE=\$(ls kk_*.zip | head -n 1)
                        
                        if [ -z "\${ZIP_FILE}" ]; then
                            echo "Error: No kk_*.zip file found"
                            exit 1
                        fi
                        
                        # Extract ver_num & timestamp from: CamBuds_<ver.name>.<timestamp>.zip
                        FILENAME=\$(basename "\${ZIP_FILE}" .zip)
                        VERSION_TIMESTAMP=\${FILENAME#kk_}
                        
                        ARTIFACT_PATH="${ARTIFACTORY}/${params['BRANCH-TARGET']}/kk_\${VERSION_TIMESTAMP}"
                        echo "Uploading to: \${ARTIFACTORY}"
                        
                        # 上传ZIP文件和MD5文件
                        if [ -f "\${ZIP_FILE}" ] && [ -f "\${ZIP_FILE}.md5" ]; then
                            curl -u "${ARTIFACTORY_CREDS}" -T "\${ZIP_FILE}" "\${ARTIFACT_PATH}/"
                            curl -u "${ARTIFACTORY_CREDS}" -T "\${ZIP_FILE}.md5" "\${ARTIFACT_PATH}/"
                        else
                            echo "Error: Package file or MD5 file not found"
                            exit 1
                        fi
                    """
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def zipFiles = sh(script: "ls ${env.OUTPUT}/kk_*.zip || true", returnStdout: true).trim().split('\n')

                    if (zipFiles.length == 0) {
                        error "No kk_*.zip files found in platform/z20k144mc/output"
                    }
                    
                    def zipFilePath = zipFiles[0]
                    // Extract file name like: kk_1.00.00.202505231230.zip
                    def zipFileName = zipFilePath.tokenize('/')[-1]
                    // Extract ver_num & timestamp from: CamBuds_<ver.name>.<timestamp>.zip
                    def versionTimestamp = zipFileName.replaceFirst('kk_', '').replace('.zip', '')
                    
                    def ftpPath = "2025 kk/Release/${params['BRANCH-TARGET']}/kk_${versionTimestamp}/"
                    
                    ftpPublisher(
                        publishers: [
                            [
                                configName: '10.10.192.11',
                                transfers: [
                                    [
                                        sourceFiles: "${env.OUTPUT}/kk_${versionTimestamp}.zip",
                                        remoteDirectory: ftpPath,
                                        removePrefix: "${env.OUTPUT}",
                                        flatten: false
                                    ],
                                    [
                                        sourceFiles: "${env.OUTPUT}/kk_${versionTimestamp}.zip.md5",
                                        remoteDirectory: ftpPath,
                                        removePrefix: "${env.OUTPUT}",
                                        flatten: false
                                    ]
                                ],
                                verbose: true
                            ]
                        ]
                    )
                }
            }
        }

        stage('UPLOAD_to_FW-MS') {
            steps {
                script {
                    def zipFiles = sh(script: "ls ${env.OUTPUT}/kk_*.zip || true", returnStdout: true).trim().split('\n')
                    
                    if (zipFiles.length == 0) {
                        error "No kk_*.zip files found in platform/z20k144mc/output"
                    }
                    
                    def zipFilePath = zipFiles[0]
                    // Extract file name like: kk_dev_1.00.00.202505231230.zip
                    def zipFileName = zipFilePath.tokenize('/')[-1]
                    // Extract file name like: kk_dev_1.00.00.202505231230
                    def releaseName = zipFileName.substring(0, zipFileName.lastIndexOf('.'))
                    
                    def md5Value = sh(script: "md5sum ${zipFilePath} | awk '{print \$1}'", returnStdout: true).trim()
                    
                    echo "Firmware MD5: ${md5Value}"
                    echo "Firmware Version: ${params.APP_VERSION}"
                    
                    def response = sh(
                        script: """
                            curl -s -w "\\n%{http_code}" -X POST -F "file=@${zipFilePath}" \\
                            -F "verCode=${env.VER_CODE}" \\
                            -F "verName=${params.APP_VERSION}" \\
                            -F "deviceTypeId=14" \\
                            -F "extType=.zip" \\
                            -F "md5=${md5Value}" \\
                            "${env.FWMS}/upload"
                        """,
                        returnStdout: true
                    ).trim()
                    
                    def responseParts = response.tokenize('\n')
                    def responseBody = responseParts[0]
                    def responseCode = responseParts[1]
                    
                    echo "API Response: ${responseBody}"
                    echo "HTTP Status Code: ${responseCode}"
                    
                    if (responseCode != "200") {
                        error "Upload failed: ${responseBody}"
                    }
                    
                    def firmwareUrl = "${env.FWMS}/download?md5=${md5Value}"
                    
                    emailext(
                        subject: "${releaseName} Firmware Release",
                        body: """
                            Dear all, <br><br>
                            We are pleased to announce the release of firmware ${releaseName}. <br>
                            Download Link: ${firmwareUrl} <br>
                            MD5: ${md5Value} <br>
                            Feedback to: jeremy.li@goertek.com
                        """,
                        to: "jeremy.li@goertek.com"
                    )
                    
                    def feishuGroups = [
                        [name: 'FEISHU_SWBJ', webhookUrl: env.FEISHU_SWBJ]
                    ]

                    feishuGroups.each { group ->
                        try {
                            def feishuMessage = """
                            {
                                "msg_type": "text",
                                "content": {
                                    "text": "KK Firmware Release ${releaseName}, which was built from the ${env.BRANCH} branch. Download: ${firmwareUrl}\\nMD5: ${md5Value}"
                                }
                            }
                            """
                            
                            httpRequest(
                                url: group.webhookUrl,
                                httpMode: 'POST',
                                contentType: 'APPLICATION_JSON',
                                requestBody: feishuMessage,
                                validResponseCodes: '200'
                            )
                            
                            echo "Feishu notification sent to ${group.name} successfully"
                        } catch (Exception e) {
                            echo "Feishu notification failed to ${group.name}: ${e.message}"
                        }
                    }
                }
            }
        }
    }
}