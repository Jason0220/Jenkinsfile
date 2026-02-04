pipeline {
    agent any

    stages {
        stage('ENV&INIT') {
            steps {
                script {
                    env.verName = params.APP_VERSION
                    echo "App Version: ${env.verName}"
                    echo "Bootloader Version: ${params.BOOTLOADER_VERSION}"
                    env.otaPath = 'target/apollo_4b_evb/tools'
                    echo "OTA_PATH: ${env.otaPath}"
                    env.verCode = params.APP_VERSION.replaceAll('[^0-9]', '')
                    echo "${env.verCode}"
                    env.targetDir = 'target/apollo_4b_evb'
                    echo "TARGET_DIR: ${env.targetDir}"
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
                    branches: [[name: "*/${params.BRANCH}"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/ResearchKit'
                    ]]
                )
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
                        sh """
                            cd "\$TARGET_DIR" || { echo "Failed to change directory to \$TARGET_DIR"; exit 1; }
                            scons -j\$(nproc) software_version="\$APP_VERSION" hardware_version=1.0 ota=ota
                        """
                    }
                }
            }
        }

        stage('PREPARE_USB_FW') {
            steps {
                script {
                   sh """
                        pwd
                        mkdir -p out/App out/OTA out/Bootloader
                        cp "${env.targetDir}"/band_app.* ./out/App/ || { echo "Failed to copy band_app.*"; exit 1; }
                        cp "${env.targetDir}"/freertos_debug.elf ./out/App/ || { echo "Failed to copy freertos_debug.elf"; exit 1; }
                        cp "${env.targetDir}"/tools/firmware/* ./out/Bootloader/ || { echo "Failed to copy Bootloader firmware"; exit 1; }
                    """
                }
            }
        }

        stage('BUILD_OTA') {
            steps {
                dir ("${env.otaPath}") {
                    script {
                        withEnv([
                            "OTA_Category=${params.OTA_Category}",
                            "APP_VERSION=${params.APP_VERSION}",
                            "BOOTLOADER_VERSION=${params.BOOTLOADER_VERSION}"
                            ]) {
                            if (params.OTA_Category == 'App-only') {
                                sh '''
                                    echo "Generating App-only OTA upgrade package"
                                    python echo_single_firmware_ota.py user region=cn version="$APP_VERSION"
                                '''
                            } else if (params.OTA_Category == 'Bootloader-only') {
                                sh '''
                                    echo "Generating Bootloader-only OTA upgrade package"
                                    python echo_single_firmware_ota.py ota region=cn version="$BOOTLOADER_VERSION"
                                '''
                            } else {
                                sh '''
                                    echo "Generating App & Bootloader_combined OTA upgrade package"
                                    python echo_single_firmware_ota.py ap_ota region=cn version="$APP_VERSION"
                                '''
                            }
                        }
                    }
                }
            }
        }
        
        stage('Prepare_OTA_File') {
            steps {
                script {
                    def upgradeFile

                    dir("${env.otaPath}") {
                        script {
                            // 1. 查找升级文件和目录
                            upgradeFile = sh(script: 'ls upgrade_[abu]*.zip 2>/dev/null || true', returnStdout: true).trim()
                            sh """
                                echo "OTA file: ${upgradeFile}"
                            """
                        }
                    }

                    dir("${env.WORKSPACE}") {
                        script {
                            withEnv([
                                "TARGET_DIR=${env.targetDir}",
                                "UPGRADE_FILE=${upgradeFile}",
                                "APP_VERSION=${params.APP_VERSION}",
                                "BRANCH=${params.BRANCH}"
                            ]) {
                                sh '''
                                    cp "$TARGET_DIR"/tools/"$UPGRADE_FILE" ./out/OTA/ || { echo "Failed to copy OTA file"; exit 1; }
                                    cd out
                                    zip -r researchKit_"$BRANCH"_"$APP_VERSION"."$(date +%Y%m%d%H%M)".zip * || { echo "Zip failed"; exit 1; }
                                    ZIP_FILE="$(ls researchKit_*.zip)"
                                    md5sum "${ZIP_FILE}" > "${ZIP_FILE}.md5"
                                '''
                            }
                            // store .zip file name to an env variable
                            env.ZIP_FILE_NAME = sh(script: 'ls out/researchKit_*.zip | head -n 1', returnStdout: true).trim()
                            echo "Generated ZIP: ${env.ZIP_FILE_NAME}"
                        }
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    dir('out') {
                        def ARTIFACTORY = "http://10.10.192.15:8081/artifactory/researchKit/Release/${params.BRANCH}"
                        
                        withCredentials([usernamePassword(
                            credentialsId: 'artifactory-jenkins', // credential
                            usernameVariable: 'ART_USER',         // define username variable and reference it in shell
                            passwordVariable: 'ART_PWD'          // define password variable and reference it in shell
                        )]) {
                            echo "Artifactory Username: ${ART_USER}"
                            
                            sh """
                                ZIP_FILE=\$(ls researchKit_*.zip | head -n 1)
                                
                                if [ -z "\${ZIP_FILE}" ]; then
                                    echo "Error: No kk_*.zip file found"
                                    exit 1
                                fi
                                
                                # Extract ver_num & timestamp from: CamBuds_<ver.name>.<timestamp>.zip
                                FILENAME=\$(basename "\${ZIP_FILE}" .zip)
                                
                                ARTIFACT_PATH="${ARTIFACTORY}/\${FILENAME}"
                                echo "Uploading to: \${ARTIFACTORY}"
                                
                                # 上传ZIP文件和MD5文件
                                if [ -f "\${ZIP_FILE}" ] && [ -f "\${ZIP_FILE}.md5" ]; then
                                    curl --fail -v -u "\${ART_USER}:\${ART_PWD}" -T "\${ZIP_FILE}" "\${ARTIFACT_PATH}/"
                                    curl --fail -v -u "\${ART_USER}:\${ART_PWD}" -T "\${ZIP_FILE}.md5" "\${ARTIFACT_PATH}/"
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

        stage('BACKUP_to_FTP') {
            steps {
                script {
                    def now = new Date()
                    def currentDate = new java.text.SimpleDateFormat("yyyyMMddHHmm").format(now)
                    def url = "2025 ResearchKit/Release/${params.BRANCH}/researchKit_${params.BRANCH}_${params.APP_VERSION}.${currentDate}"
                    
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
                                            remoteDirectory: "${url}/App/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/App",
                                            sourceFiles: "out/App/band_app.*"
                                        ],
                                        [
                                            asciiMode: false,
                                            cleanRemote: false,
                                            excludes: '',
                                            flatten: false,
                                            makeEmptyDirs: false,
                                            noDefaultExcludes: false,
                                            patternSeparator: '[, ]+',
                                            remoteDirectory: "${url}/App/",
                                            remoteDirectorySDF: false,
                                            removePrefix: "out/App",
                                            sourceFiles: "out/App/freertos_debug.elf"
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
                                            removePrefix: "out/Bootloader",
                                            sourceFiles: "out/Bootloader/*"
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
                                            removePrefix: "out/OTA",
                                            sourceFiles: "out/OTA/upgrade_*.zip"
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
                    def zipFile = sh(script: 'ls out/researchKit_*.zip || true', returnStdout: true).trim().split('\n')
                    
                    if (zipFile.length == 0) {
                        error "No researchKit_*.zip files found in out/"
                    }
                    
                    def zipFilePath = zipFile[0]
                    // Extract file name like: researchKit_1.00.00.202505231230.zip
                    def zipFileName = zipFilePath.tokenize('/')[-1]
                    // Extract ver_num & timestamp from: CamBuds_<ver.name>.<timestamp>.zip
                    def versionTimestamp = zipFileName.replaceFirst("researchKit_${params.BRANCH}_", '').replace('.zip', '')
                    
                    // Extract ver_num like: 1.00.00
                    def verName = versionTimestamp.tokenize('.').take(3).join('.')
                    
                    def md5Value = sh(script: "md5sum ${zipFilePath} | awk '{print \$1}'", returnStdout: true).trim()
                    
                    echo "Firmware MD5: ${md5Value}"
                    echo "Firmware Version: ${verName}"
                    
                    def response = sh(
                        script: """
                            curl -s -w "\\n%{http_code}" -X POST -F "file=@${zipFilePath}" \\
                            -F "verCode=${env.verCode}" \\
                            -F "verName=${verName}-${params.BRANCH}" \\
                            -F "deviceTypeId=15" \\
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
                        subject: "researchKit_${params.BRANCH}_${verName} Firmware Release",
                        body: """
                            Dear all, <br><br>
                            We are pleased to announce the release of researchKit_${params.BRANCH}_${versionTimestamp} firmware. <br>
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
                                    "text": "ResearchKit Firmware Test Build of ${params.BRANCH} branch ${versionTimestamp} has been released. Download: ${firmwareUrl}\\nMD5: ${md5Value}"
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