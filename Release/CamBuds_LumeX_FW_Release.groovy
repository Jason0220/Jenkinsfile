pipeline {
    agent any
    
    environment {
        TOOLCHAIN = "/home/data0/jenkins/tools/gcc-arm-none-eabi-10.3-2021.10/bin"
        ARTIFACTORY = "http://10.10.192.15:8081/artifactory/${params.PROJECT_NAME}/Release"
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
                    if (params.PROJECT_NAME == 'LumeX') {
                        env.PROJNAME = 'LumeX'
                        env.BRANCH = 'master'
                        env.DEVICE_TYPE_ID = '16'
                    } else if (params.PROJECT_NAME == 'CamBuds') {
                        env.PROJNAME = 'CamBuds'
                        env.BRANCH = 'cambuds'
                        env.DEVICE_TYPE_ID = '8'
                    } else {
                        error "Not supported project name: ${params.PROJECT_NAME}"  // error handling
                    }
                    
                    echo "PROJECT_NAME: ${env.PROJNAME}"
                    echo "BRANCH: ${env.BRANCH}"
                    echo "DEVICE_TYPE_ID: ${env.DEVICE_TYPE_ID}"
                    env.PATH = "/usr/bin:${env.TOOLCHAIN}:${env.PATH}"
                    sh 'which cmake'
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
                script {
                    if (!env.BRANCH) {
                        error "ERROR: env.BRANCH not initialized, cannot checkout the source code"
                    }
                    
                    echo "Ready to checkout the source code from ${env.BRANCH}."
                    
                    try {
                        checkout scmGit(
                            branches: [[name: "refs/heads/${env.BRANCH}"]], 
                            extensions: [
                                [$class: 'CleanBeforeCheckout'],
                                [$class: 'CheckoutOption', timeout: 5]
                            ], 
                            userRemoteConfigs: [[
                                credentialsId: 'Jenkins', 
                                url: 'ssh://10.10.192.13:29418/RealtekAmebaSDK'
                            ]]
                        )
                        echo "Code checkout successfully, the current branch is: ${env.BRANCH}."
                    } catch (Exception e) {
                        // error handling
                        echo "Checkout failed: ${e.message}"
                        
                        if (e.message.contains("Branch not found")) {
                            error "The required branch ${env.BRANCH} does not exist, please check the branch name!"
                        } else if (e.message.contains("Permission denied")) {
                            error "Credential authenitcation failed, please check Jenkins credential configuration!"
                        } else {
                            throw e
                        }
                    }
                }
            }
        }

        stage('BUILD') {
            options {
                timeout(time: 30, unit: 'MINUTES')
            }
            steps {
                script {
                    ansiColor('xterm') {      // Enable ANSI color support
                        sh 'sudo update-alternatives --set cmake /home/data0/jenkins/software/cmake-3.16.3-Linux-x86_64/bin/cmake'
                        sh 'cmake --version'
                        sh """
                            cd project/gtk/release
                            rm -rf build
                            mkdir -p build && cd build
                            cmake .. -G"Unix Makefiles" -DCMAKE_TOOLCHAIN_FILE=../toolchain.cmake -DGTK_HEARABLE=ON -DGTK_VERSION=${env.VER_CODE}
                            cmake --build . --target flash -j\$(nproc)
                        """
                    }
                }
            }
        }
        
        stage('Firmware_Packaging') {
            steps {
                dir('project/gtk/release/build') {
                    script {
                        // 在 Groovy 中拼接完整文件名
                        def zipFileName = "${env.PROJNAME}_${env.VER_NAME}.${new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date())}.zip"
                        
                        sh """
                            # 直接使用 Groovy 生成的文件名
                            ZIP_FILE="${zipFileName}"
                            
                            zip -r "\${ZIP_FILE}" flash_ntz.bin ota.bin || { echo "Zip failed"; exit 1; }
                            md5sum "\${ZIP_FILE}" > "\${ZIP_FILE}.md5"
                        """
                        
                        archiveArtifacts artifacts: "${env.PROJNAME}_${env.VER_NAME}*.zip", fingerprint: true
                        archiveArtifacts artifacts: "${env.PROJNAME}_${env.VER_NAME}*.zip.md5", fingerprint: true
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                dir('project/gtk/release/build') {
                    sh """
                        ZIP_FILE=\$(ls ${env.PROJNAME}_*.zip | head -n 1)
                        
                        if [ -z "\${ZIP_FILE}" ]; then
                            echo "Error: No ${env.PROJNAME}_*.zip file found"
                            exit 1
                        fi
                        
                        # Extract ver_num & timestamp from: <project_name>_<ver_name>.<timestamp>.zip
                        FILENAME=\$(basename "\${ZIP_FILE}" .zip)
                        
                        PROJECT_NAME="${env.PROJNAME}"
                        VERSION_TIMESTAMP="\${FILENAME##\${PROJECT_NAME}_}"
                        
                        ARTIFACT_PATH="${ARTIFACTORY}/\${PROJECT_NAME}_\${VERSION_TIMESTAMP}"
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
                    // transfer Jenkins environmental variable to Groovy variable in order to make it available in Shell
                    def projName = env.PROJNAME
                    
                    // use ${projName} in shell instead of ${env.PROJNAME}
                    def zipFiles = sh(
                        script: "ls project/gtk/release/build/${projName}_*.zip || true", 
                        returnStdout: true
                    ).trim().split('\n')

                    if (zipFiles.length == 0 || zipFiles[0].isEmpty()) {
                        error "No ${projName}_*.zip files found in project/gtk/release/build"
                    }
                    
                    def zipFilePath = zipFiles[0]
                    // extract file name like: CamBuds_1.00.00.202505231230.zip
                    def zipFileName = zipFilePath.tokenize('/')[-1]
                    // extract ver_name & timestampe（remove project_name & extension file name .zip）
                    def versionTimestamp = zipFileName.replaceFirst("${projName}_", '').replace('.zip', '')
                    
                    // ftp uploading path
                    def ftpPath = "2025 ${projName}/Release/${projName}_${versionTimestamp}/"
                    
                    ftpPublisher(
                        publishers: [
                            [
                                configName: '10.10.192.11',
                                transfers: [
                                    [
                                        // use Groovy file name for project_name
                                        sourceFiles: "project/gtk/release/build/${projName}_${versionTimestamp}.zip",
                                        remoteDirectory: ftpPath,
                                        removePrefix: "project/gtk/release/build",
                                        flatten: false
                                    ],
                                    [
                                        sourceFiles: "project/gtk/release/build/${projName}_${versionTimestamp}.zip.md5",
                                        remoteDirectory: ftpPath,
                                        removePrefix: "project/gtk/release/build",
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
                    def projName = env.PROJNAME
                    def deviceTypeId = env.DEVICE_TYPE_ID
                    def zipFiles = sh(script: "ls project/gtk/release/build/${projName}_*.zip || true", returnStdout: true).trim().split('\n')
                    
                    if (zipFiles.length == 0) {
                        error "No ${projName}_*.zip files found in project/gtk/release/build"
                    }
                    
                    def zipFilePath = zipFiles[0]
                    // Extract file name like: CamBuds_1.00.00.202505231230.zip
                    def zipFileName = zipFilePath.tokenize('/')[-1]
                    // Extract ver_num & timestamp from: CamBuds_<ver.name>.<timestamp>.zip
                    def versionTimestamp = zipFileName.replaceFirst("${projName}_", '').replace('.zip', '')
                    
                    // Extract ver_num like: 1.00.00
                    def verName = versionTimestamp.tokenize('.').take(3).join('.')
                    
                    def md5Value = sh(script: "md5sum ${zipFilePath} | awk '{print \$1}'", returnStdout: true).trim()
                    
                    echo "Firmware MD5: ${md5Value}"
                    echo "Firmware Version: ${verName}"
                    
                    def response = sh(
                        script: """
                            curl -s -w "\\n%{http_code}" -X POST -F "file=@${zipFilePath}" \\
                            -F "verCode=${env.VER_CODE}" \\
                            -F "verName=${verName}" \\
                            -F "deviceTypeId=${deviceTypeId}" \\
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
                        subject: "${projName}_${verName} Firmware Release",
                        body: """
                            Dear all, <br><br>
                            We are pleased to announce the release of ${projName}_${versionTimestamp} firmware. <br>
                            Download Link: ${firmwareUrl} <br>
                            MD5: ${md5Value} <br>
                            Feedback to: jeremy.li@goertek.com
                        """,
                        to: "jeremy.li@goertek.com"
                    )
                    
                    def feishuGroups = [
                        [name: 'FEISHU_SWBJ', webhookUrl: env.FEISHU_SWBJ],
//                      [name: 'FEISHU_HEARABLE_8735', webhookUrl: env.FEISHU_HEARABLE_8735]
                    ]

                    feishuGroups.each { group ->
                        try {
                            def feishuMessage = """
                            {
                                "msg_type": "text",
                                "content": {
                                    "text": "${projName} firmware ${versionTimestamp} released. Download: ${firmwareUrl}\\nMD5: ${md5Value}"
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
