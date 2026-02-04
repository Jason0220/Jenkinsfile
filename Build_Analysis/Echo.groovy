import groovy.json.JsonOutput

pipeline {
    agent any
    
    environment {
        BUILD_WRAPPER="/home/data0/jenkins/tools/build-wrapper-linux-x86"
        SONAR_SCANNER="/home/data0/jenkins/tools/sonar-scanner/bin"
        PATH="$PATH:$BUILD_WRAPPER:$SONAR_SCANNER"
        SUBMODULE_PATH = 'framework'
        SUBMODULE_REPO = 'ssh://10.10.192.13:29418/echo-framework'
    }
    
    stages {
        stage('Checkout Main Repository') {
            steps {
                checkout([
                    $class: 'GitSCM', 
                    branches: [[name: '$GERRIT_REFSPEC']], 
                    extensions: [], 
                    userRemoteConfigs: [[
                        credentialsId: 'Jenkins', 
                        refspec: 'refs/changes/*:refs/changes/*', 
                        url: 'ssh://10.10.192.13:29418/Echo'
                    ]]
                ])
            }
        }
        
        stage('Detect Submodule Changes') {
            steps {
                script {
                    // submodule has changes (reference updated)?
                    env.HAS_SUBMODULE_CHANGES = detectSubmoduleChanges(SUBMODULE_PATH) ? 'true' : 'false'
                    echo "Submodule ${SUBMODULE_PATH} has changes: ${env.HAS_SUBMODULE_CHANGES}"
                }
            }
        }
        
        stage('Checkout Submodule if Needed') {
            when {
                environment name: 'HAS_SUBMODULE_CHANGES', value: 'true'
            }
            steps {
                script {
                    // 初始化并更新子模块到最新引用版本
                    sh "git submodule init ${SUBMODULE_PATH}"
                    sh "git submodule update ${SUBMODULE_PATH}"
                    
                    // 记录子模块当前的commit ID
                    dir(SUBMODULE_PATH) {
                        env.SUBMODULE_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                        echo "Submodule current commit: ${env.SUBMODULE_COMMIT}"
                    }
                }
            }
        }
        
        stage('Execute Build&Analysis Flow') {
            steps {
                script {
                    if (env.HAS_SUBMODULE_CHANGES == 'true') {
                        echo "Detected submodule changes. Executing framework flow."
                        executeFrameworkFlow()
                    } else {
                        echo "No submodule changes. Executing main flow."
                        executeMainFlow()
                    }
                }
            }
        }

        stage('CLEAN') {
            steps {
                sh 'rm -rf framework'
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

def detectSubmoduleChanges(String submodulePath) {
    try {
        // Get the changed files
        def changedFiles = getChangedFiles()
        echo "Changed files in main repo: ${changedFiles}"
        
        // 检查是否包含.gitmodules文件变更（子模块配置变更）
        def hasGitModulesChange = changedFiles.contains('.gitmodules')
        
        // 检查子模块自身引用是否变更
        def hasSubmoduleRefChange = changedFiles.contains(submodulePath)
        
        // 两种情况都表示子模块相关变更
        return hasGitModulesChange || hasSubmoduleRefChange
    } catch (Exception e) {
        echo "Error detecting submodule changes: ${e.getMessage()}"
        return false
    }
}

// 执行主流程（无子模块变更）
def executeMainFlow() {
    stage('BUILD_MAIN_REPO') {  // Scripted 嵌套 stage，无需 steps 块
        echo "Building main repository Echo..."
        sh 'rm -rf framework'
        
        if (fileExists('target/apollo_4bp_evb')) {
            dir ('target/apollo_4bp_evb') {
                sh '''
                    git reset --hard
                    git clean -fxd
                    scons -c
                    build-wrapper-linux-x86-64 --out-dir ../../bw-output scons -j$(nproc) 
                '''
            }
        } else {
            error("Directory target/apollo_4bp_evb does not exist.")
        }
    }
    
    // 后续阶段（MAIN_REPO_CODE_ANALYSIS/Quality Gate 等）同理，若有 steps 也需删除
    stage('MAIN_REPO_CODE_ANALYSIS') {
        echo "Performing code analysis for main repository..."
        withSonarQubeEnv('SonarQube_46') {
            sh 'sonar-scanner \
                    -Dsonar.projectKey=Echo \
                    -Dsonar.sources=. \
                    -Dsonar.cfamily.build-wrapper-output=bw-output \
                    -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                    -Dsonar.login=sqp_fa76b63883b7e39b4bcfd399cfabf9fefbcd5ef0'
        }
    }

    stage("Quality Gate") {
        script {
            timeout(20) {
                def qg = waitForQualityGate('SonarQube_46')
                if (qg.status != 'OK') {
                    error "Code scan failed! failure: ${qg.status}"
                }
            }
        }
    }
    
    stage('BUILD_SUBMODULE') {
        echo "Building submodule with current reference..."
        sh "git submodule init ${SUBMODULE_PATH}"
        sh "git submodule update ${SUBMODULE_PATH}"
        dir ('target/apollo_4bp_evb') {
            sh '''
                git reset --hard
                git clean -fxd
                scons -c
                scons -j$(nproc)
            ''' 
        }
    }
}

def executeFrameworkFlow() {
    stage('BUILD_SUBMODULE') {
        echo "Building updated submodule echo-framework..."
        dir ('target/apollo_4bp_evb') {
            sh '''
                git reset --hard
                git clean -fxd
                scons -c
                build-wrapper-linux-x86-64 --out-dir ../../bw-output scons -j$(nproc)
            '''
        }
    }
    
    stage('SUBMODULE_CODE_ANALYSIS') {
        echo "Performing code analysis for submodule..."
        withSonarQubeEnv('SonarQube_46') {
            sh 'sonar-scanner \
                -Dsonar.projectKey=echo-submodule \
                -Dsonar.sources=. \
                -Dsonar.cfamily.build-wrapper-output=bw-output \
                -Dsonar.host.url=http://10.10.192.46:9000/sonarqube \
                -Dsonar.login=sqp_4562cc4f61900d4a29be09f19e6306f77cb648ad'
        }
}

    stage("Quality Gate") {
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

def getChangedFiles() {
    try {
        def gerritServer = "10.10.192.13"
        def gerritUser = "jenkins"
        
        // 使用ssh命令时指定身份文件（如果需要）
        def command = """
            ssh -p 29418 ${gerritUser}@${gerritServer} \
            gerrit query --format=JSON --current-patch-set --files change:${env.GERRIT_CHANGE_NUMBER} \
            | head -n 1 \
            | jq -r '.currentPatchSet.files[].file'
        """
        echo "Executing command: ${command}"
        
        def output = sh(script: command, returnStdout: true).trim()
        echo "Changed files output: ${output}"
        return output ? output.split('\n') : []
    } catch (Exception e) {
        echo "Error getting changed files: ${e.getMessage()}"
        // 出错时返回空列表，避免整个流水线失败
        return []
    }
}
