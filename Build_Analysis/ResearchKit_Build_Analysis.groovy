import groovy.json.JsonOutput

pipeline {
    agent { label 'jenkins-node-10.10.192.18' }
    environment {
        CONFIG_FILE = "target/apollo_4b_evb/rtconfig.h"
        BACKUP_FILE = "${CONFIG_FILE}.bak"
        SONAR_SERVER_NAME = "SonarQube_46"
        BUILD_WRAPPER="/home/jenkins/tools/build-wrapper-linux-x86/build-wrapper-linux-x86-64"
        SONAR_SCANNER="/home/jenkins/tools/sonar-scanner/bin/sonar-scanner"
    }

    stages {
        stage('CODE_Checkout') {
            options {
                retry(20)
            }
            steps {
                checkout([$class: 'GitSCM', 
                    branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/ResearchKit']]])
            }
        }
        
        stage('BUILD & ANALYSIS') {
            steps {
                script {
                    def ALGO_STATUS = [
                        [name: "algo.enable", define: "#define GTK_USING_ALGO"],
                        [name: "algo.disable", define: "// #define GTK_USING_ALGO"]
                    ]
                    def MACROS = ["CHOOSE_RESEARCHKIT_MASTER", "CHOOSE_RESEARCHKIT_RUBIS", "CHOOSE_RESEARCHKIT_PASTER"]
                    def sonarProjectMap = [
                        "CHOOSE_RESEARCHKIT_MASTER": [
                            projectKey: "ResearchKit",
                            token: "sqp_b511e56aca0f0ce80213ebb6c78d4569aa016207"
                        ],
                        "CHOOSE_RESEARCHKIT_RUBIS": [
                            projectKey: "ResearchKit_Rubis",
                            token: "sqp_18c45127bd1ae83d54fd6f97f7d1b2aad499e871"
                        ],
                        "CHOOSE_RESEARCHKIT_PASTER": [
                            projectKey: "ResearchKit_Paster",
                            token: "sqp_2af3995a3e15373b71687323e84fd96af81bc764"
                        ]
                    ]

                    def exclusionFile = "sonar_exclusions.txt"
                    def persistentPath = "/home/jenkins/sonar-files/ResearchKit/${exclusionFile}"
                    sh "if [ ! -f ${exclusionFile} ]; then cp ${persistentPath} ./; fi"    // if not exist then copy
                    def exclusionCpdFile = "sonar_cpd_exclusions.txt"
                    def persistentCpdPath = "/home/jenkins/sonar-files/ResearchKit/${exclusionCpdFile}"
                    sh "if [ ! -f ${exclusionCpdFile} ]; then cp ${persistentCpdPath} ./; fi"

                    sh '''
                        > sonar-project.properties
                            echo "sonar.sources=." >> sonar-project.properties
                            echo "sonar.sourceEncoding=UTF-8" >> sonar-project.properties
                            echo -n "sonar.exclusions=" >> sonar-project.properties
                            cat sonar_exclusions.txt >> sonar-project.properties
                            echo -n "sonar.cpd.exclusions=" >> sonar-project.properties
                            cat sonar_cpd_exclusions.txt >> sonar-project.properties    
                    '''

                    sh "cp ${CONFIG_FILE} ${BACKUP_FILE}"
                    for (def algo in ALGO_STATUS) {
                        def algoName = algo.name // enable/disable
                        def algoDefine = algo.define // #define GTK_USING_ALGO 
                        echo "====================================="
                        echo "Process ALGO status: ${algoName} (${algoDefine})"
                        echo "====================================="

                        for (def macro in MACROS) {
                            if (algoName == "algo.enable" && macro == "CHOOSE_RESEARCHKIT_PASTER") {
                                echo "Skip this iteration and proceed to the next one: algoName=${algoName} && macro=${macro}"
                                continue
                            }
                            
                            macro = macro.trim()
                            def comboKey = "${algoName}_${macro}"
                            def sonarConfig = sonarProjectMap[macro]
                            def projectKey = sonarConfig.projectKey
                            def sonarToken = sonarConfig.token
                            
                            echo "====================================="
                            echo "Process the combination: ${algoName} + ${macro}"
                            echo "SonarQube projectKey: ${projectKey}"
                            echo "====================================="
                            
                            sh """
                                cp ${BACKUP_FILE} ${CONFIG_FILE}
                            
                                if grep -q "define GTK_USING_ALGO" ${CONFIG_FILE}; then
                                    target_line='${algoDefine}'
                                    sed -i 's|.*define GTK_USING_ALGO.*|'"\${target_line}"'|g' ${CONFIG_FILE}
                                fi
                                # verify the modification
                                grep -n "define GTK_USING_ALGO" ${CONFIG_FILE}

                                # comment out all the target Macros
                                for macro_name in ${MACROS.join(' ')}; do
                                    sed -i 's|^#define '"\${macro_name}"'|// #define '"\${macro_name}"'|g' ${CONFIG_FILE}
                                done
                                # enable the Macro
                                sed -i 's|^// #define ${macro}|#define ${macro}|g' ${CONFIG_FILE}
                                # verify the modification
                                grep -n "define ${macro}" ${CONFIG_FILE}
                            """
                            
                            echo "Building ${comboKey} ..."
                            try {
                                sh """
                                    cd target/apollo_4b_evb
                                    scons -c
                                    ${BUILD_WRAPPER} --out-dir ../../bw-output_${comboKey} scons -j\$(nproc)
                                """
                            } catch (Exception e) {
                                error("${comboKey} building failed: ${e.getMessage()}")
                            }
                            
                            echo "Begin ${comboKey} SonarQube Analysis ..."
                            withSonarQubeEnv('SonarQube_46') {
                                sh """
                                    ${SONAR_SCANNER} \
                                        -Dsonar.branch.name=${env.GERRIT_BRANCH} \
                                        -Dsonar.projectKey=${projectKey} \
                                        -Dsonar.projectName=${projectKey} \
                                        -Dsonar.cfamily.build-wrapper-output=bw-output_${comboKey} \
                                        -Dsonar.host.url=http://10.10.192.46:9000/sonarqube   \
                                        -Dsonar.login=${sonarToken}
                                """
                            }
                            
                            def qualityGate = waitForQualityGate() // waiting for Quality Gate
                            if (qualityGate.status != 'OK') {
                                error("${comboKey} Failed to pass the Quality Gate! (Status: ${qualityGate.status}），STOP!")
                            }
                            echo "${comboKey} Code Analysis Passed!"
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'Build successful - performing cleanup'
            sh 'rm -rf *'
        }
        always {  // 无论构建成功或失败都执行
            script {
                def consoleLog = currentBuild.rawBuild.getLog(100) // 获取全部日志

                // Gerrit 相关信息
                def gerritServer = "http://10.10.192.13:8082/gerrit"  // Gerrit 服务器地址
                def changeId = env.GERRIT_CHANGE_NUMBER             // Gerrit Change ID
                def revisionId = env.GERRIT_PATCHSET_NUMBER         // Gerrit Patchset (Revision) ID
                def gerritUser = "jenkins"                    // Gerrit 用户名
                def gerritPassword = "L4Yjc6ypq0kLEhdJ9dl/1wUCgC2dWv9WI7Br2mIUdw"                // Gerrit 用户密码

                // 格式化日志为代码块并加入换行符
                def formattedLog = "```\n" + consoleLog.join("\n") + "\n```"

                // 创建要发送的 JSON 数据
                def jsonData = JsonOutput.toJson([message: "Jenkins Console Log:\n${formattedLog}"])

                // 使用 curl 通过 Gerrit REST API 发送评论
                sh """
                curl -X POST --data '${jsonData}' \\
                        -H 'Content-Type: application/json' \\
                        --user '${gerritUser}:${gerritPassword}' \\
                        "${gerritServer}/a/changes/${changeId}/revisions/${revisionId}/review"
                """
            }
        }
    }
}