pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    stages {
        stage('ENV_INIT') {
            steps {
                script {
                    cleanWs()
                    echo "App Version: ${params.APP_VERSION}"
                    env.verCode = params.APP_VERSION.replaceAll('[^0-9]', '')
                    echo "${env.verCode}"
                    echo "PRODUCT: ${params.PRODUCT}"
                }
            }
        }
        
        stage('FTP Download') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'FTP-10.10.192.11', 
                        usernameVariable: 'FTP_USER',
                        passwordVariable: 'FTP_PASS'
                    )
                ]) {
                    script {
                        def remotePath = "/SW/Project/2025%20Spinel_Rubis/Release/${params.PRODUCT}/${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}/${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}.zip"
                        def localFile = "${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}.zip"
                        
                        sh """
                        set -e
                        FTP_URL='ftp://10.10.192.11${remotePath}'
                        echo "FTP Address: \${FTP_URL}"
                        curl -f -v --path-as-is -u "\${FTP_USER}:\${FTP_PASS}" -o "${localFile}" "\${FTP_URL}"
                        if [ ! -f "${localFile}" ]; then
                            echo "Error: Download failed, the local file was not found: ${localFile}"
                            exit 1
                        fi
                        if [ \$(stat -c%s "${localFile}") -eq 0 ]; then
                            echo "Error: The downloaded file is empty: ${localFile}"
                            exit 1
                        fi
                        echo "FTP download successfully! File size: \$(stat -c%s "${localFile}") Bytes"
                    """
                    }
                }
            }
        }

        stage('BACKUP_to_Artifactory') {
            steps {
                script {
                    def ARTIFACTORY = "http://10.10.192.15:8081/artifactory/${params.PRODUCT}/Release/${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}/"
                    echo "${ARTIFACTORY}"

                    withCredentials([usernamePassword(
                        credentialsId: 'artifactory-jenkins',
                        usernameVariable: 'ART_USER',
                        passwordVariable: 'ART_PWD'
                    )]) {
                        echo "Artifactory Username: ${ART_USER}"
                        
                        sh """
                            ZIP_FILE=\$(ls ${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}.zip | head -n 1)
                            if [ -z "\${ZIP_FILE}" ]; then
                                echo "Error: No ${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}.zip file found"
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

        stage('UPLOAD_to_FW-MS') {
            steps {
                script {
                    def FWMS = "https://alpha.goertek.com:8883/api/firmware"
                    def deviceTypeId = 0
                    def zipFile = sh(
                        script: "find . -maxdepth 1 -name '${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}.zip' -type f | head -n 1",
                        returnStdout: true
                    ).trim()
                    
                    if (zipFile.length() == 0) {
                        error "No ${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP}.zip file found in the workspace"
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
                        subject: "${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP} Firmware Release",
                        body: """
                            Dear all, <br><br>
                            We are pleased to announce the release of ${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP} firmware. <br>
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
                                    "text": "${params.PRODUCT} firmware ${params.PRODUCT}_fw_${params.APP_VERSION}.${params.DATE_TIMESTAMP} released. \\nDownload: ${firmwareUrl}\\nMD5: ${md5Value}"
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