pipeline {
    agent any

    stages {
        stage('ENV&INIT') {
            steps {
                script {
                    env.branch = params.BRANCH
                    echo "BRANCH: ${env.branch}"
                    env.verName = params.APP_VERSION
                    echo "Version: ${env.verName}"
                    env.otaPath = 'framework/ota/tools'
                    echo "OTA_PATH: ${env.otaPath}"
                    env.verCode = params.APP_VERSION.replaceAll('\\.', '')
                    echo "${env.verCode}"
                    
                    echo "deviceTypeID: 3"
                    echo "deviceType name: ar_band_c5_1"

                    env.targetDir = 'target/apollo_4b_evb'
                    echo "TARGET_DIR: ${env.targetDir}"
                    env.upgradePath = ''
                    env.otaFileName = ''
                }
            }
        }

        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout scmGit(branches: [[name: "*/${params.BRANCH}"]], 
                extensions: [], 
                userRemoteConfigs: [[credentialsId: 'Jenkins', 
                name: 'origin', 
                refspec: "+refs/heads/${params.BRANCH}:refs/remotes/origin/${params.BRANCH}", 
                url: 'ssh://10.10.192.13:29418/AR_band']])
            }
        }

        stage('BUILD_USB_DEBUG_FW') {
            steps {
                script {
                    withEnv([
                        "APP_VERSION=${params.APP_VERSION}",
                        "TARGET_DIR=${env.targetDir}"
                        ]) {
                        sh """
                            git reset --hard
                            git clean -fxd
                            cd "\$TARGET_DIR" || { echo "Failed to change directory to \$TARGET_DIR"; exit 1; }
                            scons -j\$(nproc) version="\$APP_VERSION" package=base
                        """
                    }
                }
            }
        }

        stage('USB_DEBUG_FW_PACKAGING') {
            steps {
                script {
                   sh """
                        pwd
                        mkdir -p out/App_debug
                        cp "${env.targetDir}"/ar_band.* ./out/App_debug/ || { echo "Failed to copy commaii_ap.*"; exit 1; }
                        cp "${env.targetDir}"/base_*.hex ./out/App_debug/ || { echo "Failed to copy base_*.hex"; exit 1; }
                    """
                }
            }
        }

        stage('BUILD_OTA') {
            steps {
                dir ("${env.otaPath}") {
                    script {
                        withEnv([
                            "APP_VERSION=${params.APP_VERSION}"
                        ]) {
                            sh '''
                                echo "Generating OTA upgrade package"
                                python package_single_firmware.py user version="$APP_VERSION"
                            '''
                        }
                    }
                }
            }
        }
        
        stage('Prepare_OTA_bin') {
            steps {
                dir("${env.otaPath}") {
                    script {
                        // 1. 查找升级文件和目录
                        def upgradePath = sh(script: 'ls -d upgrade_[au]* 2>/dev/null || true', returnStdout: true).trim()
                        def upgradeFile = sh(script: """
                            if [ -n "$upgradePath" ]; then 
                                ls "$upgradePath"/upgrade* 2>/dev/null || true;
                            fi
                        """, returnStdout: true).trim()

                        if (!upgradePath || !upgradeFile) {
                            error "Failed to find upgrade path or file"
                        }

                        // 2. 构造新文件名
                        def newBaseName = "ar_band_${params.BRANCH}_${params.APP_VERSION}"
                        def fileNameParts = upgradeFile.split('/')
                        def fileNameWithoutUpgrade = fileNameParts.last().replace('upgrade', '')
                        
                        // 3. 持久化环境变量（在 withEnv 外部赋值）
                        env.upgradePath = upgradePath
                        echo "upgradePath: ${env.upgradePath}"
                        env.otaFileName = "${newBaseName}${fileNameWithoutUpgrade}"
                        echo "otaFileName: ${env.otaFileName}"

                        // 4. 重命名文件
                        sh """
                            echo "Renaming file: ${fileNameParts[-1]} to ${env.otaFileName}"
                            mv "$upgradeFile" "$upgradePath/${env.otaFileName}"
                            if [ \$? -ne 0 ]; then
                                echo "Failed to rename file"
                                exit 1
                            fi
                        """

                        // 5. 列出重命名后的文件
                        sh """
                            echo "Listing OTA file in $upgradePath after renaming:"
                            ls "$upgradePath/${env.otaFileName}"
                        """
                    }
                }

                dir("${env.WORKSPACE}") {
                    script {
                        // 确保路径以 / 分隔
                        def otaFilePath = "${env.otaPath}/${env.upgradePath}/${env.otaFileName}".replace('//', '/')
                        echo "otaFilePath: ${otaFilePath}"
                        
                        sh """
                            pwd
                            mkdir -p out/App_release out/Bootloader out/OTA
                            cp "${env.targetDir}"/ar_band.* ./out/App_release/ || { echo "Failed to copy commaii_ap.*"; exit 1; }
                            cp "${env.otaPath}"/firmware/commaii_ota.* ./out/Bootloader/ || { echo "Failed to copy firmware"; exit 1; }
                            cp "$otaFilePath" ./out/OTA/ || { echo "Failed to copy OTA file"; exit 1; }
                            
                            cd out
                            zip -r "ar_band_${params.BRANCH}_${params.APP_VERSION}.\$(date +%Y%m%d%H%M).zip" * || { echo "Zip failed"; exit 1; }
                        """
                        
                        // 获取生成的 ZIP 文件名
                        env.ZIP_FILE_NAME = sh(script: 'ls out/ar_band_*.zip | head -n 1', returnStdout: true).trim()
                        echo "Generated ZIP: ${env.ZIP_FILE_NAME}"
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    withEnv([
                        "BRANCH=${params.BRANCH}",
                        "APP_VERSION=${params.APP_VERSION}",
                        "TARGET_DIR=${env.targetDir}",
                        "otaPath=${env.otaPath}",
                        "upgradePath=${env.upgradePath}",
                        "otaFileName=${env.otaFileName}"
                        ]) {
                        sh '''
                            url="http://10.10.192.15:8081/artifactory/AR_band/Release/\$APP_VERSION.\$(date +%Y%m%d%H%M)"
                            echo \$url
                            userpass="Jenkins:Beijing123"
                            for file in out/ar_band_*.zip; do
                                if [ -f "\$file" ]; then
                                    curl -u "\$userpass" -T "\$file" "\$url/"
                                fi
                            done
                        '''
                    }
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def now = new Date()
                    def currentDate = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(now)
                    def url = "2025 AR_band/Release/${params.BRANCH}/ar_band_${params.BRANCH}_${params.APP_VERSION}.${currentDate}"
                    
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
                                            remoteDirectory: "${url}/App_debug/",
                                            remoteDirectorySDF: false,
                                            removePrefix: env.targetDir,
                                            sourceFiles: "${env.targetDir}/base_*.hex"
                                        ],
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}/App_debug/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/App_debug",
                                            sourceFiles: "out/App_debug/ar_band.*"
                                        ],
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}/App_release/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/App_release",
                                            sourceFiles: "out/App_release/ar_band.*"
                                        ],
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}/Bootloader/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "${env.otaPath}/firmware/",
                                            sourceFiles: "${env.otaPath}/firmware/commaii_ota.*"
                                        ],
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}/OTA/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "${env.otaPath}/${upgradePath}",
                                            sourceFiles: "${env.otaPath}/${upgradePath}/${otaFileName}"
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
                    def feishuWebhookUrl = "https://open.feishu.cn/open-apis/bot/v2/hook/6f4997f3-1d60-48fa-923d-3f8a33779807"
                    def EXPORT_FILE_NAME
                    def EXPORT_MD5

                    // 执行 sh 脚本块，获取 MD5 值和文件名
                    def output = sh(
                        script: """
                            url="https://alpha.goertek.com:8883/api/firmware/upload"
                            echo \$url
                            filePath="${env.ZIP_FILE_NAME}"
                            for file in \$filePath; do
                                if [ -f "\$file" ]; then
                                    MD5_VAL=\$(md5sum "\$file" | awk '{print \$1}')
                                    echo "The MD5 value of the file \$file is: \$MD5_VAL"
                                    echo "Uploading \$file to \$url"
                                    curl -v -X POST -F "file=@\$file" -F "verCode=${env.verCode}" \
                                    -F "verName=${env.verName}" -F "deviceTypeId=3" -F "extType=.zip" \
                                    -F "md5=\$MD5_VAL" "\$url"
                                    if [ \$? -ne 0 ]; then
                                        echo "Failed to upload \$file"
                                        exit 1
                                    fi
                                    echo "Sending email for \$file"
                                    FILE_NAME=\$(basename "\$file")
                                    export EXPORT_FILE_NAME=\$FILE_NAME
                                    export EXPORT_MD5=\$MD5_VAL
                                    # 输出变量值，用于 Groovy 脚本获取
                                    echo "\$EXPORT_FILE_NAME"
                                    echo "\$EXPORT_MD5"
                                fi
                            done
                        """,
                        returnStdout: true
                    ).trim().split('\n')

                    // 打印 output 数组的长度
                    println "output 数组的长度为: ${output.length}"

                    // 循环打印 output 数组中的每个元素
                    for (int i = 0; i < output.length; i++) {
                        println "output[$i]: ${output[i]}"
                    }

                    if (output.length == 6) {
                        EXPORT_FILE_NAME = output[4]
                        EXPORT_MD5 = output[5]

                        emailext(
                            subject: "${EXPORT_FILE_NAME} AR_band branch:${params.BRANCH} FW Release",
                            body: "Dear all, <br><br>We're pleased to announce the release of ${EXPORT_FILE_NAME} for branch:${params.BRANCH}. <br>Download from https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}. <br>Feedback is welcomed at jeremy.li@goertek.com",
                            to: "jeremy.li@goertek.com"
                        )
                    }

                    // 构建飞书消息的 JSON 数据
                    def message = """
                    {
                        "msg_type": "text",
                        "content": {
                            "text": "AR_band branch:${params.BRANCH} firmware release ${env.verName} is available, please downoad it from the following link: https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}"
                        }
                    }
                    """

                    // 使用 httpRequest 插件发送 POST 请求到飞书 Webhook
                    try {
                        def response = httpRequest contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: message, url: feishuWebhookUrl
                        if (response.status == 200) {
                            echo "The firmware download link has been successfully sent to the Feishu group."
                        } else {
                            echo "Sending failed, HTTP status code: ${response.status}"
                        }
                    } catch (Exception e) {
                        echo "An error occurred while sending the request: ${e.message}"
                    }
                }
            }
        }
    }
}