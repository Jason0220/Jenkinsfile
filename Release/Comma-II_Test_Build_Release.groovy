pipeline {
    agent any

    environment {
        PROJECT_DIR = "${HOME}/virtual_environment/comma-ii"
        PROJECT_VENV_PATH = "${HOME}/virtual_environment/comma-ii/venv"
    }
    
    stages {
        stage('ENV&INIT') {
            steps {
                script {
                    env.sku = params.SKU
                    echo "SKU: ${env.sku}"
                    env.verName = params.APP_VERSION
                    echo "Version: ${env.verName}"
                    env.otaPath = 'framework/ota/tools'
                    echo "OTA_PATH: ${env.otaPath}"
                    env.verCode = params.APP_VERSION.replaceAll('\\.', '')
                    echo "${env.verCode}"
                    
                    if (params.SKU == 'sku1') {
                        env.deviceTypeId = 5
                        env.targetDir = 'target/apollo_4b_evb'
                    } else if (params.SKU == 'sku2') {
                        env.deviceTypeId = 6
                        env.targetDir = 'target/apollo_4b_sku2'
                    } else if (params.SKU == 'ua') {
                        env.deviceTypeId = 7
                        env.targetDir = 'target/ua_customer'
                    } else if (params.SKU == 'research_kit') {
                        env.deviceTypeId = 15
                        env.targetDir = 'target/researchkit_ring'
                    } else if (params.SKU == 'sku1_factory') {
                        env.deviceTypeId = 5
                        env.targetDir = 'target/sku1_factory'
                    } else if (params.SKU == 'sku2_factory') {
                        env.deviceTypeId = 6
                        env.targetDir = 'target/sku2_factory'
                    } else {
                        error "Unsupported SKU value: ${params.SKU}. Supported values are 'sku1', 'sku2' and 'ua'."
                    }
                    echo "deviceTypeID: ${env.deviceTypeId}"
                    echo "TARGET_DIR: ${env.targetDir}"
                    env.upgradePath = ''
                    env.otaFileName = ''
                }
            }
        }
        
        stage('PYTHON_VENV') {
            steps {
                script {
                    if (!fileExists("${PROJECT_VENV_PATH}/bin/activate")) {
                        echo "venv not available, creating Python 3.9 venv..."
                        dir("${PROJECT_DIR}") {
                            sh 'python -m venv venv'
                        }
                    } else {
                        echo "venv is available!"
                    }
                }
            }
        }
        
        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout scmGit(branches: [[name: '*/Apollo4L']], 
                extensions: [], 
                userRemoteConfigs: [[credentialsId: 'Jenkins', 
                name: 'origin', 
                refspec: '+refs/heads/Apollo4L:refs/remotes/origin/Apollo4L', 
                url: 'ssh://10.10.192.13:29418/Comma']])
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
                    if (params.Download_Patch_4) {
                        sh "${params.Download_Patch_4}"
                    }
                    if (params.Download_Patch_5) {
                        sh "${params.Download_Patch_5}"
                    }
                    if (params.Download_Patch_6) {
                        sh "${params.Download_Patch_6}"
                    }
                }
            }
        }

        stage('BUILD_USB_FW') {
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

        stage('PREPARE_USB_FW_for_PACKAGING') {
            steps {
                script {
                   sh """
                        pwd
                        mkdir -p out/App_USB
                        cp "${env.targetDir}"/commaii_ap.* ./out/App_USB/ || { echo "Failed to copy commaii_ap.*"; exit 1; }
                        cp "${env.targetDir}"/base_*.hex ./out/ || { echo "Failed to copy base_*.hex"; exit 1; }
                    """
                }
            }
        }

        stage('BUILD_OTA') {
            steps {
                dir("${env.otaPath}") {
                    script {
                        withEnv([
                            "SKU=${params.SKU}",
                            "APP_VERSION=${params.APP_VERSION}"
                        ]) {
                            sh '''
                                echo "Activating venv..."
                                source ${PROJECT_VENV_PATH}/bin/activate
                                
                                echo "Python version: "
                                python --version
                                
                                echo "pip version: "
                                pip --version
                                
                                echo "Installing project dependencies..."
                                pip install -r ${PROJECT_DIR}/requirements.txt
                                
                                if [ "$SKU" = "sku1" ] || [ "$SKU" = "sku2" ] || [ "$SKU" = "ua" ] || [ "$SKU" = "research_kit" ]; then
                                    if [ "$OTA" = "Full" ]; then
                                        echo "Generating Full OTA upgrade package"
                                        python ota_packager.py type="$SKU" version="$APP_VERSION"
                                    else
                                        echo "Generating App OTA upgrade package"
                                        python package_single_firmware.py user_"$SKU" version="$APP_VERSION"
                                    fi
                                elif [ "$SKU" = "sku1_factory" ]; then
                                    if [ "$OTA" = "Full" ]; then
                                        echo "Generating Full OTA upgrade package"
                                        python ota_packager.py type=factory_sku1 version="$APP_VERSION"
                                    else
                                        echo "Generating App OTA upgrade package"
                                        python package_single_firmware.py factory_sku1 version="$APP_VERSION"
                                    fi
                                elif [ "$SKU" = "sku2_factory" ]; then
                                    if [ "$OTA" = "Full" ]; then
                                        echo "Generating Full OTA upgrade package"
                                        python ota_packager.py type=factory_sku2 version="$APP_VERSION"
                                    else
                                        echo "Generating App OTA upgrade package"
                                        python package_single_firmware.py factory_sku2 version="$APP_VERSION"
                                    fi
                                fi
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
                        def upgradePath = sh(script: '''
                            shopt -s extglob
                            ls -d upgrade_@(all|user|factory)_* 2>/dev/null || true
                            ''', returnStdout: true).trim()
                        def upgradeFile = sh(script: """
                            if [ -n "$upgradePath" ]; then 
                                ls "$upgradePath"/upgrade* 2>/dev/null || true;
                            fi
                        """, returnStdout: true).trim()

                        if (!upgradePath || !upgradeFile) {
                            error "Failed to find upgrade path or file"
                        }

                        // 2. 构造新文件名
                        def newBaseName = "commaii_${params.SKU}_${params.APP_VERSION}"
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
                            mkdir -p out/App_OTA out/Bootloader out/OTA
                            cp "${env.targetDir}"/commaii_ap.* ./out/App_OTA/ || { echo "Failed to copy commaii_ap.*"; exit 1; }
                            cp "${env.otaPath}"/firmware/*.* ./out/Bootloader/ || { echo "Failed to copy firmware"; exit 1; }
                            cp "$otaFilePath" ./out/OTA/ || { echo "Failed to copy OTA file"; exit 1; }
                            
                            cd out
                            zip -r "commaii_${params.SKU}_${params.APP_VERSION}.\$(date +%Y%m%d%H%M).zip" * || { echo "Zip failed"; exit 1; }
                        """
                        
                        // 获取生成的 ZIP 文件名
                        env.ZIP_FILE_NAME = sh(script: 'ls out/commaii_*.zip | head -n 1', returnStdout: true).trim()
                        echo "Generated ZIP: ${env.ZIP_FILE_NAME}"
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    withEnv([
                        "SKU=${params.SKU}",
                        "APP_VERSION=${params.APP_VERSION}",
                        "TARGET_DIR=${env.targetDir}",
                        "otaPath=${env.otaPath}",
                        "upgradePath=${env.upgradePath}",
                        "otaFileName=${env.otaFileName}"
                        ]) {
                        sh """
                            url="http://10.10.192.15:8081/artifactory/comma-ii-\$SKU/Release/\$APP_VERSION.\$(date +%Y%m%d%H%M)"
                            echo \$url
                            userpass="Jenkins:Beijing123"
                            for file in "\$TARGET_DIR"/base_*.hex; do
                                if [ -f "\$file" ]; then
                                    curl -u "\$userpass" -T "\$file" "\$url/"
                                fi
                            done
                            # 上传 USB commaii_ap.* 文件
                            for file in ./out/App_USB/commaii_ap.*; do
                                if [ -f "\$file" ]; then
                                    curl -u "\$userpass" -T "\$file" "\$url/App_USB/"
                                    if [ \$? -ne 0 ]; then
                                        echo "Failed to upload file \$file"
                                        exit 1
                                    fi
                                fi
                            done
                            # 上传 OTA commaii_ap.* 文件
                            for file in ./out/App_OTA/commaii_ap.*; do
                                if [ -f "\$file" ]; then
                                    curl -u "\$userpass" -T "\$file" "\$url/App_OTA/"
                                    if [ \$? -ne 0 ]; then
                                        echo "Failed to upload file \$file"
                                        exit 1
                                    fi
                                fi
                            done

                            # 上传 commaii_ota.* 文件
                            for file in "\$otaPath"/firmware/commaii_ota.*; do
                                if [ -f "\$file" ]; then
                                    curl -u "\$userpass" -T "\$file" "\$url/Bootloader/"
                                    if [ \$? -ne 0 ]; then
                                        echo "Failed to upload file \$file"
                                        exit 1
                                    fi
                                fi
                            done

                            # 上传 commaii_secure.* 文件
                            for file in "\$otaPath"/firmware/commaii_secure.*; do
                                if [ -f "\$file" ]; then
                                    curl -u "\$userpass" -T "\$file" "\$url/Bootloader/"
                                    if [ \$? -ne 0 ]; then
                                        echo "Failed to upload file \$file"
                                        exit 1
                                    fi
                                fi
                            done

                            # 上传 version.txt 文件
                            file="\$otaPath/firmware/version.txt"
                            if [ -f "\$file" ]; then
                                curl -u "\$userpass" -T "\$file" "\$url/Bootloader/"
                                if [ \$? -ne 0 ]; then
                                    echo "Failed to upload file \$file"
                                    exit 1
                                fi
                            fi

                            # 上传特定文件
                            file="\$otaPath/\$upgradePath/\$otaFileName"
                            if [ -f "\$file" ]; then
                                curl -u "\$userpass" -T "\$file" "\$url/OTA/"
                                if [ \$? -ne 0 ]; then
                                    echo "Failed to upload file \$file"
                                    exit 1
                                fi
                            fi
                        """
                    }
                }
            }
        }

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def now = new Date()
                    def currentDate = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(now)
                    def url = "2024 Comma-II/Release/${params.SKU}/commaii_${params.SKU}_${params.APP_VERSION}.${currentDate}"
                    
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
                                            remoteDirectory: "${url}/App_USB/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/App_USB",
                                            sourceFiles: "out/App_USB/commaii_ap.*"
                                        ],
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}/App_OTA/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/App_OTA",
                                            sourceFiles: "out/App_OTA/commaii_ap.*"
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
                                            remoteDirectory: "${url}/Bootloader/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "${env.otaPath}/firmware/",
                                            sourceFiles: "${env.otaPath}/firmware/commaii_secure.*"
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
                                            sourceFiles: "${env.otaPath}/firmware/version.txt"
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
                                            removePrefix: "${env.otaPath}",
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
                                    -F "verName=${env.verName}" -F "deviceTypeId=${env.deviceTypeId}" -F "extType=.zip" \
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
                            subject: "${EXPORT_FILE_NAME} Comma-II FW Release",
                            body: "Dear all, <br><br>We're pleased to announce the release of ${EXPORT_FILE_NAME} for ${params.SKU} user variant. <br>Download from https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}. <br>Feedback is welcomed at jeremy.li@goertek.com",
                            to: "jeremy.li@goertek.com"
                        )
                    }

                    // 构建飞书消息的 JSON 数据
                    def message = """
                    {
                        "msg_type": "text",
                        "content": {
                            "text": "Comma-II ${env.sku} firmware release ${env.verName} for user variant is available, please downoad it from the following link: https://alpha.goertek.com:8883/api/firmware/download?md5=${EXPORT_MD5}"
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
