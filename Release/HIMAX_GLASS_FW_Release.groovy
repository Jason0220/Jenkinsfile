pipeline {
    agent any

    stages {
        stage('ENV&INIT') {
            steps {
                script {
                    env.verName = params.APP_VERSION
                    echo "App Version: ${env.verName}"
                    env.verCode = params.APP_VERSION.replaceAll('[^0-9]', '')
                    echo "${env.verCode}"
                }
            }
        }

        stage('Code_Checkout') {
            options {
                retry(3)
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                checkout scmGit(
                    branches: [[name: "*/${params.BRANCH}"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/HIMAX_GLASS'
                    ]]
                )
            }
        }

        stage('BUILD_FW') {
            steps {
                script {
                    withEnv([
                        "APP_VERSION=${params.APP_VERSION}",
                        "TARGET_DIR=${env.targetDir}"
                        ]) {
                        def resetResult = sh script: 'git reset --hard', returnStatus: true
                        if (resetResult != 0) {
                            error("git reset --hard execution failed! Exit code: ${resetResult}")
                        } else {
                            echo "git reset --hard executed successfully: Workspace reset to latest commit state"
                        }

                        def cleanResult = sh script: 'git clean -fxd', returnStatus: true
                        if (cleanResult != 0) {
                            error("git clean -fxd execution failed! Exit code: ${cleanResult}")
                        } else {
                            echo "git clean -fxd executed successfully: All untracked files and directories removed"
                        }
                        sh '''
                            make clean && make all
                        '''

                    }
                }
            }
        }

        stage('PREPARE_FW') {
            steps {
                script {
                    def outDir = "we2_image_gen_local/output_case1_secboot_nodiv"
                    
                    if (!fileExists(outDir)) {
                        error("❌ Error: Firmware directory not found -> ${outDir}")
                    }
                    
                    dir (outDir) {
                        env.timestamp = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date())
                        if (!fileExists("output.img")) {
                            error("❌ Error: Artifact missing -> ${outDir}/output.img")
                        }
                        
                        def targetImgName = "Rubis_NPU_${params.APP_VERSION}.${env.timestamp}.img"
                        sh "mv output.img ${targetImgName}"
                        
                        echo "✅ Firmware rename success! Target file: ${targetImgName}"
                        env.TARGET_IMG_NAME = targetImgName
                    }
                }
            }
        }


        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    def outDir = "we2_image_gen_local/output_case1_secboot_nodiv"
                    dir(outDir) {
                        def ARTIFACTORY = "http://10.10.192.15:8081/artifactory/HIMAX_GLASS/Release/${params.BRANCH}"
                        
                        withCredentials([usernamePassword(
                            credentialsId: 'artifactory-jenkins', // credential
                            usernameVariable: 'ART_USER',         // define username variable and reference it in shell
                            passwordVariable: 'ART_PWD'          // define password variable and reference it in shell
                        )]) {
                            echo "Artifactory Username: ${ART_USER}"
                            
                            sh """
                                # Extract basename from e.g. Rubis_NPU_0.0.1.202512251221.img
                                FILENAME=\$(basename "${env.TARGET_IMG_NAME}" .img)
                                
                                ARTIFACT_PATH="${ARTIFACTORY}/\${FILENAME}"
                                echo "Uploading to: \${ARTIFACTORY}"
                                
                                # 上传ZIP文件和MD5文件
                                if [ -f "${env.TARGET_IMG_NAME}" ] && [ -f "\${FILENAME}.md5" ]; then
                                    curl --fail -v -u "\${ART_USER}:\${ART_PWD}" -T "${env.TARGET_IMG_NAME}" "\${ARTIFACT_PATH}/"
                                    curl --fail -v -u "\${ART_USER}:\${ART_PWD}" -T "${FILENAME}.md5" "\${ARTIFACT_PATH}/"
                                else
                                    echo "Error: FW file or MD5 file not found"
                                    exit 1
                                fi
                            """
                        }
                    }
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def url = "2025 Spinel_Rubis/Release/rubis/NPU-HIMAX_GLASS/${params.BRANCH}"
                    def outDir = "we2_image_gen_local/output_case1_secboot_nodiv"
                    ftpPublisher(
                            alwaysPublishFromMaster: false,
                            continueOnError: false,
                            failOnError: false,
                            publishers: [
                                [
                                    configName: '10.10.192.11',
                                    transfers: [
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}",
                                            remoteDirectorySDF: false,
                                            removePrefix: "${outDir}",
                                            sourceFiles: "${outDir}/${env.TARGET_IMG_NAME}"
                                        ]
                                    ],
                                    usePromotionTimestamp: false,
                                    useWorkspaceInPromotion: false,
                                    verbose: false
                                ]
                            ]
                    )
                }
            }
        }

        stage('UPLOAD_to_FW-MS') {
            steps {
                script {
                    def FWMS = "https://alpha.goertek.com:8883/api/firmware"
                    def imgFile = "${env.TARGET_IMG_NAME}"
                    def imgFilePath = "we2_image_gen_local/output_case1_secboot_nodiv/${env.TARGET_IMG_NAME}"
                    
                    def md5Value = sh(script: "md5sum ${imgFilePath} | awk '{print \$1}'", returnStdout: true).trim()
                    
                    echo "Firmware MD5: ${md5Value}"
                    echo "Firmware Version: ${params.APP_VERSION}"
                    
                    def response = sh(
                        script: """
                            curl -s -w "\\n%{http_code}" -X POST -F "file=@${imgFilePath}" \\
                            -F "verCode=${env.verCode}" \\
                            -F "verName=${params.APP_VERSION}" \\
                            -F "deviceTypeId=23" \\
                            -F "extType=.img" \\
                            -F "md5=${md5Value}" \\
                            "${FWMS}/upload"
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
                    
                    def firmwareUrl = "${FWMS}/download?md5=${md5Value}"
                    
                    emailext(
                        subject: "Rubis NPU of HIMAX_GLASS Firmware version ${params.APP_VERSION} Release",
                        body: """
                            Dear all, <br><br>
                            We are pleased to announce the release of Rubis NPU of HIMAX_GLASS firmware version ${params.APP_VERSION} Release. <br>
                            Download Link: ${firmwareUrl} <br>
                            MD5: ${md5Value} <br>
                            Feedback to: jeremy.li@goertek.com
                        """,
                        to: "jeremy.li@goertek.com"
                    )
                    
                    def feishuGroups = [
                        [name: 'FEISHU_SPINEL_RUBIS', webhookUrl: env.FEISHU_SPINEL_RUBIS]
                    ]

                    feishuGroups.each { group ->
                        try {
                            def feishuMessage = """
                            {
                                "msg_type": "text",
                                "content": {
                                    "text": "Rubis NPU of HIMAX_GLASS Firmware of ${params.APP_VERSION} released. Download: ${firmwareUrl}\\nMD5: ${md5Value}"
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