pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    stages {
        stage('ENV_VAR') {
            steps {
                script {
                    env.verName = params.VERSION_NAME
                    echo "${env.verName}"
                    env.verCode = env.verName.replaceAll('\\.', '')
                    echo "${env.verCode}"
                    env.outDir = 'app/build/outputs/apk/release'
                    echo "${env.outDir}"
                }
            }
        }

        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: '*/master']],
                    extensions: [],
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins',
                        name: 'origin',
                        refspec: '+refs/heads/master:refs/remotes/origin/master',
                        url: 'ssh://10.10.192.13:29418/Skindot'
                    ]]
                ])
            }
        }

        stage('BUILD') {
            steps {
                script {
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

                    def versionFilePath = 'app/build.gradle.kts'
                    def fileContent = readFile(file: versionFilePath)
                    fileContent = fileContent.replaceFirst(/versionName\s*=\s*"[^"]+"/, "versionName = \"${env.verName}\"")
                    fileContent = fileContent.replaceFirst(/versionCode\s*=\s*\d+/, "versionCode = ${env.verCode}")
                    writeFile file: versionFilePath, text: fileContent
                    echo "Updated build.gradle.kts version info:"
                    fileContent.split('\n').each { line ->
                        if (line.contains('versionName =') || line.contains('versionCode =')) {
                            echo line.trim()
                        }
                    }

                    def propFilePath = 'local.properties'
                    if (fileExists(propFilePath)) {
                        def propContent = readFile(file: propFilePath)
                        propContent = propContent.replaceFirst(/sdk.dir=.+/, "sdk.dir=/home/jenkins/Android/Sdk")
                        writeFile file: propFilePath, text: propContent
                        echo "Updated local.properties file:"
                        sh 'cat local.properties | grep sdk.dir'
                    } else {
                        echo "Warning: ${propFilePath} does not exist, creating it..."
                        writeFile file: propFilePath, text: "sdk.dir=/home/jenkins/Android/Sdk\n"
                    }

                    withEnv([
                        "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64",
                        "ANDROID_SDK=/home/jenkins/Android/Sdk"
                    ]) {
                        sh '''
                            export JAVA_HOME=$JAVA_HOME
                            if [ ! -x ./gradlew ]; then
                                echo "gradlew script is not executable or does not exist."
                                exit 1
                            fi
                            ./gradlew clean
                            if [ $? -ne 0 ]; then
                                echo "Gradle build failed. Check logs for details."
                                exit 1
                            fi
                            ./gradlew assembleRelease
                            if [ $? -ne 0 ]; then
                                echo "Gradle build failed. Check logs for details."
                                exit 1
                            fi
                        '''
                    }

                    env.TIMESTAMP = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(new Date())
                    env.targetName = "skindot_${params.VERSION_NAME}.${env.TIMESTAMP}.apk"
                    echo "Build Timestamp: ${env.TIMESTAMP}" 
                    def targetFile = "app-release.apk"
                    dir("${env.outDir}") {
                        if (fileExists(targetFile)) {
                            sh "mv ${targetFile} ${env.targetName}"
                            echo "File ${targetFile} renamed: ${env.targetName}"
                        } else {
                            error "Error: File ${targetFile} does not exist, cannot rename!"
                        }
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    sh """
                        url="http://10.10.192.15:8081/artifactory/skindot/Release/${env.verName}.${env.TIMESTAMP}"
                        echo \$url
                        for file in \$outDir/${env.targetName}; do
                            if [ -f "\$file" ]; then
                                echo "Uploading \$file to \$url"
                                curl -u Jenkins:Beijing123 -T "\$file" "\$url/"
                                if [ \$? -ne 0 ]; then
                                    echo "Failed to upload \$file"
                                    exit 1
                                fi
                            fi
                        done
                    """
                }
            }
        }

        stage('BACKUP_FW_to_FTP') {
            steps {
                script {
                    def now = new Date()
                    def currentDate = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(now)
                    def url = "2026 skindot/Release/${env.verName}.${env.TIMESTAMP}/"

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
                                            remoteDirectory: url,
                                            remoteDirectorySDF: false,
                                            removePrefix: env.outDir,
                                            sourceFiles: "${env.outDir}/${env.targetName}"
                                        ],
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

                    withEnv([
                        "outDir=${env.outDir}",
                        "VER_CODE=${env.verCode}",
                        "VER_NAME=${env.verName}"
                    ]) {
                        // 执行 sh 脚本块，获取 MD5 值和文件名
                        def output = sh(
                            script: """
                                url="https://alpha.goertek.com:8883/api/firmware/upload"
                                echo \$url
                                filePath="${outDir}/skindot_*.apk"
                                for file in \$filePath; do
                                    if [ -f "\$file" ]; then
                                        MD5_VAL=\$(md5sum "\$file" | awk '{print \$1}')
                                        echo "The MD5 value of the file \$file is: \$MD5_VAL"
                                        echo "Uploading \$file to \$url"
                                        curl -v -X POST -F "file=@\$file" -F "verCode=\$VER_CODE" \
                                        -F "verName=\$VER_NAME" -F "deviceTypeId=24" -F "extType=.apk" \
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
                                subject: "${EXPORT_FILE_NAME} skindot Phone App Release",
                                body: "Dear all, <br><br>we're pleased to announce the release of ${EXPORT_FILE_NAME}. <br>Download from https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}. <br>Feedback is welcome at jeremy.li@goertek.com",
                                to: "jeremy.li@goertek.com"
                            )
                        }
                    }

                    // 构建飞书消息的 JSON 数据
                    def message = """
                    {
                        "msg_type": "text",
                        "content": {
                            "text": "skindot Phone App ${env.verName} is available, please downoad it from the following link: https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}"
                        }
                    }
                    """

                    // 使用 httpRequest 插件发送 POST 请求到飞书 Webhook
                    try {
                        def response = httpRequest contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: message, url: feishuWebhookUrl
                        if (response.status == 200) {
                            echo "固件下载链接已成功发送到飞书群组。"
                        } else {
                            echo "发送失败，HTTP 状态码: ${response.status}"
                        }
                    } catch (Exception e) {
                        echo "发送请求时出现错误: ${e.message}"
                    }
                }
            }
        }
    }
}