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
    }
}