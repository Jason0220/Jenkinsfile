pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    stages {
        stage('ENV&INIT') {
            steps {
                script {
                    env.verName = params.APP_VERSION
                    echo "App Version: ${env.verName}"
                    env.verCode = params.APP_VERSION.replaceAll('[^0-9]', '')
                    echo "${env.verCode}"
                    env.product = params.PRODUCT
                    echo "PRODUCT: ${env.product}"
                    echo "BRANCH: ${params.BRANCH}"
                }
            }
        }

        stage('CODE_SYNC') {
            options {
                retry(3)
                timeout(time: 10, unit: 'MINUTES')
            }
            steps {
                cleanWs()
                checkout scmGit(
                    branches: [[name: "*/${params.BRANCH}"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/BES2800BP_GLASS'
                    ]]
                )
            }
        }

        stage('BUILD_USB_FW') {
            steps {
                script {
                    def configFile = "${env.WORKSPACE}/tools/${params.PRODUCT}_config.sh"
                    echo "${params.PRODUCT} Version File: ${configFile}"
                    
                    def configFileContent = sh(script: "cat ${configFile}", returnStdout: true).trim()
                    def targetLinePattern = ~/LILY_VERSION_CFG="LILY_SOFTWARE_VERSION=\d+\.\d+\.\d+"/
                    def versionLine = configFileContent.split("\n").find { it =~ targetLinePattern }
                    echo "Original Version: ${versionLine}"

                    if (versionLine) {
                        def newVersionLine = versionLine.replaceAll(targetLinePattern) { match ->
                            "LILY_VERSION_CFG=\"LILY_SOFTWARE_VERSION=${params.APP_VERSION}\""
                        }
                        echo "New Version: ${newVersionLine}"
                        configFileContent = configFileContent.replace(versionLine, newVersionLine)
                        echo "File updated successfully."
                    } else {
                        error("No matching version line found in the config file.")
                    }

                    sh(script: "echo '${configFileContent}' > '${configFile}'", returnStdout: true)
                    echo "File updated successfully."

                    def cleanResult = sh script: 'git clean -fxd', returnStatus: true
                    if (cleanResult != 0) { 
                        error("git clean -fxd execution failed! Exit code: ${cleanResult}") 
                    } else { 
                        echo "git clean -fxd executed successfully: All untracked files and directories removed" 
                    } 
                    
                    if (params.PRODUCT == 'spinel') { 
                        sh './compile.sh spinelA || { echo "Failed to execute ./compile.sh"; exit 1; }' 
                    } else if (params.PRODUCT == 'rubis') { 
                        sh './compile.sh Rubis || { echo "Failed to execute ./compile.sh"; exit 1; }' 
                    } 
                    env.timestamp = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date()) 
                } 
            } 
        }

        stage('BUILD_OTA') {
            steps {
                script {
                    sh '''
                        echo "Generating OTA upgrade package"
                        ./tools/ota/make_ota.sh
                    '''
                }
            }
        }

        stage('Packaging_Firmware') {
            steps {
                script {
                    if (params.PRODUCT == 'spinel') {
                        sh '''
                            cp tools/dldtool/release/ota_boot_watch.bin out/release/
                            cp --parents -r vx100_fw/ out/release/
                        '''
                    } else if (params.PRODUCT == 'rubis') {
                        sh '''
                            cp tools/dldtool/release/ota_boot_watch_rubis.bin out/release/
                            cp apps/app_uikit/image.bin out/release/
                            cp -r out/ota out/release/
                        '''
                    }
                    dir('out/release') {
                        zip( 
                            zipFile: "${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip", 
                            archive: true, 
                            dir: '.'
                        )
                    }
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def url = "2025 Spinel_Rubis/Release/${params.PRODUCT}/${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}"
                    
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
                                            remoteDirectory: "${url}/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/release/",
                                            sourceFiles: "out/release/${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip"
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

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    dir('out/release') {
                        def ARTIFACTORY = "http://10.10.192.15:8081/artifactory/${params.PRODUCT}/Release/${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}/"
                        echo "${ARTIFACTORY}"

                        withCredentials([usernamePassword(
                            credentialsId: 'artifactory-jenkins',
                            usernameVariable: 'ART_USER',
                            passwordVariable: 'ART_PWD'
                        )]) {
                            echo "Artifactory Username: ${ART_USER}"
                            
                            sh """
                                ZIP_FILE=\$(ls ${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip | head -n 1)
                                if [ -z "\${ZIP_FILE}" ]; then
                                    echo "Error: No ${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip file found"
                                    exit 1
                                fi

                                # generating MD5
                                md5sum "\${ZIP_FILE}" > "\${ZIP_FILE}.md5"
                                ART_DIR="${ARTIFACTORY}"
                                echo "Uploading to: \${ART_DIR}"
                                # uploading ZIP file and MD5 file
                                if [ -f "\${ZIP_FILE}" ] && [ -f "\${ZIP_FILE}.md5" ]; then
                                    curl --fail -v -u "\${ART_USER}:\${ART_PWD}" -T "\${ZIP_FILE}" "\${ART_DIR}"
                                    curl --fail -v -u "\${ART_USER}:\${ART_PWD}" -T "\${ZIP_FILE}.md5" "\${ART_DIR}"
                                else
                                    echo "Error: Package file or MD5 file not found"
                                    exit 1
                                fi
                            """
                        }    
                    }
                }
            }
        }

        stage('UPLOAD_to_FW-MS') {
            steps {
                script {
                    dir('out/release') {
                        def FWMS = "https://alpha.goertek.com:8883/api/firmware"
                        def deviceTypeId = 0
                        def zipFile = sh(
                            script: "find . -maxdepth 1 -name '${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip' -type f | head -n 1",
                            returnStdout: true
                        ).trim()
                        
                        if (zipFile.length() == 0) {
                            error "No ${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip file found in the workspace"
                        }
                        
                        def md5Value = sh(script: "md5sum ${zipFile} | awk '{print \$1}'", returnStdout: true).trim()
                        echo "Firmware MD5: ${md5Value}"
                        echo "Firmware Version: ${params.APP_VERSION}"
                        
                        if (params.PRODUCT == 'spinel') {
                            deviceTypeId = 19
                        } else if (params.PRODUCT == 'rubis') {
                            deviceTypeId = 20
                        }

                        def response = sh(
                            script: """
                                curl -s -w "\\n%{http_code}" -X POST -F "file=@${zipFile}" \\
                                -F "verCode=${env.verCode}" \\
                                -F "verName=${params.APP_VERSION}" \\
                                -F "deviceTypeId=${deviceTypeId}" \\
                                -F "extType=.zip" \\
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
                            subject: "${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp} Firmware Release",
                            body: """
                                Dear all, <br><br>
                                We are pleased to announce the release of ${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp} firmware. <br>
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
                                        "text": "${params.PRODUCT} firmware ${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp} released. \\nDownload: ${firmwareUrl}\\nMD5: ${md5Value}"
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
}