import groovy.json.JsonOutput

pipeline {
    agent any
    environment {
        BUILD_WRAPPER="~/tools/build-wrapper-linux-x86"
        SONAR_SCANNER="~/tools/sonar-scanner/bin"
        PATH="$PATH:$BUILD_WRAPPER:$SONAR_SCANNER"
    }

    stages {
        stage('CODE_SYNC') {
            options {
                retry(20)
            }
            steps {
                checkout([$class: 'GitSCM', 
                    branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/Comma']]])
            
            echo "The Changes belong to the Gerrit Branch: ${env.GERRIT_BRANCH}"
            echo "Current Refspec: ${env.GERRIT_REFSPEC}"
            }
        }
        
        stage('BUILD_ua') {
            steps {
                dir ('target/ua_customer') {
                    sh """
                        scons -c
                        git reset --hard
                        git clean -fxd
                        build-wrapper-linux-x86-64 --out-dir ../../bw-output_ua scons -j\$(nproc)
                    """
                }
            }
        }
        
        stage('SCAN_ua') {
            when {
                not {
                    equals expected: 'Apollo4L_Secure', actual: env.GERRIT_BRANCH
                }
            }
            
            steps {
                withSonarQubeEnv('SonarQube_46') {
                    script {
                        def version = sh(
                            script: 'git describe --tags --always',
                            returnStdout: true
                        ).trim()
                        echo "PREVIOUS VERSION = ${version}"

                        def sonarExclusions = """drivers/ppg/pah8151/factory_test/pah_factory_test_v3/**/*,
                            drivers/algo_sensor/nnom/inc/**/*,
                            drivers/algo_sensor/nnom/src/**/*,
                            drivers/algo_sensor/nnom/port/**/*,
                            drivers/algo_sensor/nnom/c_model/**/*,
                            drivers/algo_sensor/nnom/filter/**/*,
                            drivers/algo_sensor/nnom/test*.h,
                            drivers/algo_sensor/nnom/cmsis_lib/**/*,
                            drivers/algo_sensor/nnom/weights*.h,
                            third_party/**/*,
                            framework/ota/component/ota_zip/minizip/**/*,
                            framework/ota/component/ota_zip/quickLZ/**/*,
                            framework/ota/component/diff_algo/algo/**/*,
                            framework/ota/component/check/algo/**/*,
                            target/**/common/*,
                            target/**/rtconfig.h,
                            platform/**/*,
                            tools/**/*,
                            framework/base/cm_backtrace/**/*,
                            framework/base/ulog/backend/rtt/**/*,
                            drivers/algo_sensor/gomore_health/gomore_core/sku1_gomore_lib/**/*,
                            drivers/algo_sensor/gomore_health/gomore_core/sku2_gomore_lib/**/*,
                            target/apollo_4b_evb/**/*,  // 修正原中文逗号为英文逗号
                            **/*.css,**/*.js,**/*.html,**/*.xml,**/*.py,**/*.php,**/*.yaml"""
                            .replaceAll(/\s+/, ' ').trim()  // 去除换行和多余空格

                        def sonarCpdExclusions = """target/apollo_4b_evb/**/*,
                            target/apollo_4b_sku2/**/*,
                            target/sku1_factory/**/*,
                            target/sku2_factory/**/*,
                            target/PCVR/**/*,
                            target/researchkit_ring/**/*,
                            target/standard_all_ring/**/*,
                            target/upSportHearSet/**/*"""
                            .replaceAll(/\s+/, ' ').trim()

                        sh """
                            > sonar-project.properties
                            
                            echo "sonar.projectKey=Comma-II_inmo_dev" >> sonar-project.properties
                            echo "sonar.exclusions=${sonarExclusions}" >> sonar-project.properties
                            echo "sonar.cpd.exclusions=${sonarCpdExclusions}" >> sonar-project.properties
                            
                            sonar-scanner \
                                -Dsonar.projectKey=Comma-II_inmo_dev \
                                -Dsonar.branch.name=${env.GERRIT_BRANCH}   \
                                -Dsonar.projectName="Comma-II_inmo_dev" \
                                -Dsonar.projectVersion="${version}" \
                                -Dsonar.sources=. \
                                -Dsonar.cfamily.build-wrapper-output=./bw-output_ua \
                                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                                -Dsonar.login=sqp_1aee7a92c6d53221dc10c52b5a6147c135abb9fe
                        """
                    }
                }
            }
        }
        
        stage("Quality Gate_ua") {
            when {
                not {
                    equals expected: 'Apollo4L_Secure', actual: env.GERRIT_BRANCH
                }
            }
            
            steps {
                script {
                    timeout(20) {
                        def qg = waitForQualityGate('SonarQube_46')
                        if (qg.status != 'OK') {
                            error "Code scan failed! failure: ${qg.status}"
                        }
                    }
                }
            }
        }
    }
    
    post {
        always {
            script {
                def consoleLog = currentBuild.rawBuild.getLog(100)
                def gerritServer = "http://10.10.192.13:8082/gerrit"
                def changeId = env.GERRIT_CHANGE_NUMBER
                def revisionId = env.GERRIT_PATCHSET_NUMBER
                
                def formattedLog = "```\n" + consoleLog.join("\n") + "\n```"
                def jsonData = JsonOutput.toJson([message: "Jenkins Console Log:\n${formattedLog}"])
    
                try {
                    httpRequest (
                        url: "${gerritServer}/a/changes/${changeId}/revisions/${revisionId}/review",
                        httpMode: 'POST',
                        contentType: 'APPLICATION_JSON',
                        requestBody: jsonData,
                        // 使用有效的凭据ID，而不是用户名:密码字符串
                        authentication: 'jenkins_http_password'
                    )
                    echo "Successfully sent comment to Gerrit"
                } catch (Exception e) {
                    echo "Failed to send a comment to Gerrit: ${e.getMessage()}"
                }
            }
        }
    }
}