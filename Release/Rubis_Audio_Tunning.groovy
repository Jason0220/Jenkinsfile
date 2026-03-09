pipeline {
    agent { label 'jenkins-node-10.10.192.18' }
    parameters {
        // Defines a parameter named 'AUDIO_FILE' for uploading a file
        base64File('AUDIO_FILE') 
    }

    stages {
        stage('ENV&INIT') {
            steps {
                script {
                    env.verName = params.APP_VERSION
                    echo "App Version: ${env.verName}"
                    env.product = params.PRODUCT
                    echo "PRODUCT: ${env.product}"
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
                    branches: [[name: "*/spinel_app"]], 
                    extensions: [[$class: 'CleanBeforeCheckout']],
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        url: 'ssh://10.10.192.13:29418/BES2800BP_GLASS'
                    ]]
                )
            }
        }

        stage('PATCH') {
            steps {
                script {
                    sh 'git fetch ssh://10.10.192.13:29418/BES2800BP_GLASS refs/changes/48/7248/1 && git cherry-pick FETCH_HEAD'
                }
            }
        }

        stage('BUILD_USB_FW') {
            steps {
                script {
                    sh '''
                        rm -rf config/best1700_glass/tgt_hardware.c
                        ls -l config/best1700_glass/
                    '''
                    def targetFile = "config/best1700_glass/tgt_hardware.c"
                    def targetDir = "config/best1700_glass"

                    if (params.AUDIO_FILE) {
                        echo "--- 正在使用系统 base64 命令还原文件 ---"
                        sh """
                            echo '${params.AUDIO_FILE}' | base64 --decode > ${targetFile}
                            dos2unix -f ${targetFile}
                            echo "--- File Preview ---"
                            ls -l ${targetDir}/
                            cat ${targetFile}
                        """
                        echo "The file has been successfully copied to the project directory: ${targetDir}"
                    }
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
                            # cp tools/dldtool/release/ota_boot_watch_rubis.bin out/release/
                            # cp apps/app_uikit/image.bin out/release/
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
    }

    post {
        success {
            emailext(
                to: 'jeremy.li@goertek.com,holiday.qin@goertek.com',
                subject: "✅ ${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp} FW Release - Jenkins 构建成功：${JOB_NAME} #${BUILD_NUMBER}",
                from: 'daily_build.jobs@goertek.com',
                replyTo: 'jeremy.li@goertek.com',
                
                body: """
                    <h3>构建结果通知</h3>
                    <p>任务名称：${JOB_NAME}</p>
                    <p>构建编号：${BUILD_NUMBER}</p>
                    <p>构建状态：成功</p>
                    <p>构建日志：<a href="${BUILD_URL}console">点击查看</a></p>
                    <p>产物说明：本次构建的 Firmware 已作为附件发送</p>
                """,
                
                attachmentsPattern: "out/release/${params.PRODUCT}_fw_${params.APP_VERSION}.${env.timestamp}.zip",
            )
        }
        
        failure {
            emailext(
                to: 'jeremy.li@goertek.com',
                subject: "❌ Jenkins 构建失败：${JOB_NAME} #${BUILD_NUMBER}",
                body: """
                    <h3>构建失败通知</h3>
                    <p>任务名称：${JOB_NAME}</p>
                    <p>构建编号：${BUILD_NUMBER}</p>
                    <p>失败原因：请查看构建日志 → <a href="${BUILD_URL}console">点击查看</a></p>
                """
            )
        }
    }
}