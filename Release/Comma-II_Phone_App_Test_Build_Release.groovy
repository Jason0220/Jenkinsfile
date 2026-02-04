pipeline {
    agent any

    stages {
        stage('ENV_VAR') {
            steps {
                script {
                    env.sku = params.SKU
                    echo "${env.sku}"
                    env.verName = params.VERSION_NAME
                    echo "${env.verName}"
                    env.verCode = env.verName.replaceAll('\\.', '')
                    echo "${env.verCode}"
                    env.buildDir = 'GTBluetoothTools'
                    echo "${env.buildDir}"

                    if (params.SKU == 'Comma') {
                        env.custName = 'Comma'
                    } else if (params.SKU == 'Ua') {
                        env.custName = 'Urtopia'
                    } else if (params.SKU == 'researchkit') {
                        env.custName = 'ResearchKit'
                    } else if (params.SKU == 'Kxr') {
                        env.custName = 'KXR'
                    } else if (params.SKU == 'U09') {
                        env.custName = 'U09'
                    } else if (params.SKU == 'seres') {
                        env.custName = 'seres'
                    } else if (params.SKU == 'commainter') {
                        env.custName = 'commainter'
                    } else {
                        error "Unsupported SKU value: ${params.SKU}. Supported values are 'Comma', 'Ua', ‘researchkit’. 'Kxr', 'U09', 'seres' and 'commainter'."
                    }
                    echo "${env.custName}"
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
                    branches: [[name: '*/comma']],
                    extensions: [],
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins',
                        name: 'origin',
                        refspec: '+refs/heads/comma:refs/remotes/origin/comma',
                        url: 'ssh://10.10.192.13:29418/Comma_PhoneApp'
                    ]]
                ])
            }
        }

        stage('PATCH') {
            steps {
                script {
                    if (params.Download_Patch_1) {
                        sh "${params.Download_Patch_1}"
                    }
                    if (params.Download_Patch_2) {
                        sh "${params.Download_Patch_2}"
                    }
                    if (params.Download_Patch_3) {
                        sh "${params.Download_Patch_3}"
                    }
                }
            }
        }
        
        stage('BUILD') {
            steps {
                script {
                    sh 'git reset --hard'
                    sh 'git clean -fxd'

                    def versionFile = 'GTBluetoothTools/versions.gradle'
                    def fileContent = readFile(file: versionFile)
                    fileContent = fileContent.replaceFirst(/build_versions.name = "[^"]+"/, "build_versions.name = \"${env.verName}\"")
                    fileContent = fileContent.replaceFirst(/build_versions.code = \d+/, "build_versions.code = ${env.verCode}")
                    writeFile file: versionFile, text: fileContent
                    println "Updated versions.gradle file: \n${fileContent}"

                    withEnv([
                        "JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64",
                        "ANDROID_SDK=/home/data0/jenkins/software/Android/Sdk",
                        "ANDROID_NDK=/home/data0/jenkins/software/Android/Sdk/ndk/21.4.7075529"
                    ]) {
                        sh """
                            cd "${env.buildDir}" || { echo "Failed to change directory to ${env.buildDir}"; exit 1; }
                            export JAVA_HOME=$JAVA_HOME
                            echo sdk.dir=$ANDROID_SDK > local.properties
                            echo ndk.dir=$ANDROID_NDK >> local.properties
                            if [ ! -x ./gradlew ]; then
                                echo "gradlew script is not executable or does not exist."
                                exit 1
                            fi
                            ./gradlew clean
                            if [ \$? -ne 0 ]; then
                                echo "Gradle clean task failed. Check clean.log for details."
                                exit 1
                            fi
                            # 去掉重定向，直接将日志输出到Jenkins Console
                            ./gradlew assemble${env.sku}Release
                            if [ \$? -ne 0 ]; then
                                echo "Gradle assemble task failed."
                                exit 1
                            fi
                        """
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    def skuLowerCase = env.sku.toLowerCase()
                    def outDir = "GTBluetoothTools/app/build/outputs/apk/${skuLowerCase}/release"

                    withEnv([
                        "outDir=${outDir}"
                    ]) {
                        sh """
                            url="http://10.10.192.15:8081/artifactory/comma-ii_Phone_App/Release/${env.custName}/${env.verName}.\$(date +%Y%m%d%H%M)"
                            echo \$url
                            for file in \$outDir/GTBluetoothTools_*.apk; do
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
        }

        stage('BACKUP_FW_to_FTP') {
            steps {
                script {
                    def now = new Date()
                    def currentDate = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(now)
                    def url = "2024 Comma-II/Release/PhoneApp/${env.custName}/${params.SKU}_PhoneApp_${env.verName}.${currentDate}/"
                    def skuLowerCase = env.sku.toLowerCase()
                    def outDir = "GTBluetoothTools/app/build/outputs/apk/${skuLowerCase}/release/"

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
                                            removePrefix: outDir,
                                            sourceFiles: "${outDir}GTBluetoothTools_*.apk"
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
                    def skuLowerCase = env.sku.toLowerCase()
                    def outDir = "GTBluetoothTools/app/build/outputs/apk/${skuLowerCase}/release"
                    def feishuWebhookUrl = "https://open.feishu.cn/open-apis/bot/v2/hook/6f4997f3-1d60-48fa-923d-3f8a33779807"
                    def EXPORT_FILE_NAME
                    def EXPORT_MD5

                    withEnv([
                        "outDir=${outDir}",
                        "VER_CODE=${env.verCode}",
                        "VER_NAME=${env.verName}"
                    ]) {
                        // 执行 sh 脚本块，获取 MD5 值和文件名
                        def output = sh(
                            script: """
                                url="https://alpha.goertek.com:8883/api/firmware/upload"
                                echo \$url
                                filePath="${outDir}/GTBluetoothTools_*.apk"
                                for file in \$filePath; do
                                    if [ -f "\$file" ]; then
                                        MD5_VAL=\$(md5sum "\$file" | awk '{print \$1}')
                                        echo "The MD5 value of the file \$file is: \$MD5_VAL"
                                        echo "Uploading \$file to \$url"
                                        curl -v -X POST -F "file=@\$file" -F "verCode=\$VER_CODE" \
                                        -F "verName=\$VER_NAME" -F "deviceTypeId=2" -F "extType=.apk" \
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
                                subject: "${EXPORT_FILE_NAME} Phone App Release",
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
                            "text": "Phone App ${env.verName} for ${env.custName} is available, please downoad it from the following link: https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}"
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
