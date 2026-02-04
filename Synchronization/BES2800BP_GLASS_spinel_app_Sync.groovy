pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    options {
        skipDefaultCheckout() // skip the default stage of checkout SCM
        timestamps()
    }
    
    environment {
        GERRIT_BJ_URL = 'ssh://10.10.192.13:29418/BES2800BP_GLASS'
        GERRIT_BJ_CRED_ID = 'privateKey-10.10.192.18'
        GERRIT_BJ_BRANCH = 'spinel_app'

        GERRIT_YFPT_URL = 'ssh://yfpt.goertek.com:29418/Spinel'
        GERRIT_YFPT_CRED_ID = 'privateKey-10.10.192.18'
        GERRIT_YFPT_BRANCH = 'master'
    }

    stages {
        stage('Preparation') {
            steps {
                script {
                    echo "===== Trigger of GERRIT_BJ ====="
                    echo "Commit Hash: ${env.GERRIT_PATCHSET_REVISION}"
                    echo "Change ID: ${env.GERRIT_CHANGE_ID}"
                    echo "======================================"

                    if (!env.GERRIT_PATCHSET_REVISION) {
                        error("Failed to retrieve the commit hash of Gerrit A. Trigger event is abnormal!")
                    }
                }
            }
        }

        stage('Repo-Init_Remote Config') {
            steps {
                script {
                    sh "git init || true"

                    // config Upstream（GERRIT_BJ）
                    withCredentials([sshUserPrivateKey(
                        credentialsId: env.GERRIT_BJ_CRED_ID,
                        keyFileVariable: 'GERRIT_BJ_KEY',
                        usernameVariable: 'jenkins'
                    )]) {
                        sh '''
                            GIT_SSH_COMMAND="ssh -i ${GERRIT_BJ_KEY} -o StrictHostKeyChecking=no" && \
                            if ! git remote | grep -q "^upstream$"; then
                                git remote add upstream ''' + env.GERRIT_BJ_URL + '''
                            fi && \
                            git remote -v
                        '''
                    }
                    
                    // config origin (GERRIT_YFPT)
                    withCredentials([sshUserPrivateKey(
                        credentialsId: env.GERRIT_YFPT_CRED_ID,
                        keyFileVariable: 'GERRIT_YFPT_KEY',
                        usernameVariable: 'jeremy.li'
                    )]) {
                        sh '''
                            GIT_SSH_COMMAND="ssh -i ${GERRIT_YFPT_KEY} -o StrictHostKeyChecking=no" && \
                            if ! git remote | grep -q "^origin$"; then
                                git remote add origin ''' + env.GERRIT_YFPT_URL + '''
                            fi && \
                            git remote -v
                        '''
                    }
                }
            }
        }

        stage('Fetch') {
            steps {
                script {
                    // Pull GERRIT_YFPT master branch
                    withCredentials([sshUserPrivateKey(
                        credentialsId: env.GERRIT_YFPT_CRED_ID,
                        keyFileVariable: 'GERRIT_YFPT_KEY'
                    )]) {
                        sh '''
                            GIT_SSH_COMMAND="ssh -i ${GERRIT_YFPT_KEY} -o User=jeremy.li -o StrictHostKeyChecking=no" \
                            git pull origin ''' + env.GERRIT_YFPT_BRANCH + ''' --rebase
                        '''
                    }

                    // Fetch GERRIT_BJ spinel_app branch
                    withCredentials([sshUserPrivateKey(
                        credentialsId: env.GERRIT_BJ_CRED_ID,
                        keyFileVariable: 'GERRIT_BJ_KEY'
                    )]) {
                        sh '''
                            GIT_SSH_COMMAND="ssh -i ${GERRIT_BJ_KEY} -o StrictHostKeyChecking=no" \
                            git fetch upstream ''' + env.GERRIT_BJ_BRANCH + '''
                        '''
                    }
                }
            }
        }

        stage('Cherry-Pick & Push') {
            steps {
                script {
                    def commitHash = env.GERRIT_PATCHSET_REVISION
                    echo "===== Start Cherry-Pick Commit: ${commitHash} ====="

                    // Clean stale operation states
                    withCredentials([sshUserPrivateKey(
                        credentialsId: env.GERRIT_YFPT_CRED_ID,
                        keyFileVariable: 'GERRIT_YFPT_KEY'
                    )]) {
                        sh '''
                            echo "===== Clean up workspace and reset branch ====="
                            git reset HEAD --hard || true
                            git clean -fxd || true
                            git cherry-pick --abort || true
                            git checkout ''' + env.GERRIT_YFPT_BRANCH + ''' || true
                            GIT_SSH_COMMAND="ssh -i ${GERRIT_YFPT_KEY} -o User=jeremy.li -o StrictHostKeyChecking=no" \
                            git pull origin ''' + env.GERRIT_YFPT_BRANCH + ''' --rebase || true
                        '''
                    }

                    echo "===== Execute cherry-pick for commit: ${commitHash} ====="
                    def cherryPickExitCode = sh(
                        script: "git cherry-pick ${commitHash}",
                        returnStatus: true
                    )

                    // cherry-pick error handling
                    def isPickSuccess = false
                    if (cherryPickExitCode == 0) {
                        echo "✅ Cherry-Pick ${commitHash} success"
                        isPickSuccess = true
                    } else {
                        // Whether empty commit or not?
                        def isEmptyCommit = sh(
                            script: "git status | grep -q 'nothing to commit' && git status | grep -q 'cherry-pick'",
                            returnStatus: true
                        )
                        if (isEmptyCommit == 0) {
                            echo "ℹ️ Cherry-Pick ${commitHash} is empty: The change already exists in the target branch!"
                            echo "ℹ️ Skip empty commit, no need to push"
                            isPickSuccess = true
                        } else {
                            error("❌ Cherry-Pick Commit ${commitHash} FAILED! Code conflicts exist, please resolve manually.")
                        }
                    }

                    // push to Gerrit YFPT when cherry-pick success
                    if (isPickSuccess) {
                        withCredentials([sshUserPrivateKey(
                            credentialsId: env.GERRIT_YFPT_CRED_ID,
                            keyFileVariable: 'GERRIT_YFPT_KEY'
                        )]) {
                            echo "===== Push commit to Gerrit YFPT: ${env.GERRIT_YFPT_BRANCH} ====="
                            sh '''
                                GIT_SSH_COMMAND="ssh -i ${GERRIT_YFPT_KEY} -o User=jeremy.li -o StrictHostKeyChecking=no" \
                                git push origin HEAD:refs/for/''' + env.GERRIT_YFPT_BRANCH + ''' || {
                                    echo "❌ Push to Gerrit failed, check permission/network"
                                    exit 1
                                }
                            '''
                            echo "✅ Commit ${commitHash} pushed to GERRIT_YFPT ${env.GERRIT_YFPT_BRANCH} for review successfully!"
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo "===== Code Sync Completed Successfully ====="
            echo "GERRIT_BJ Commit: ${env.GERRIT_PATCHSET_REVISION}"
            echo "Synchronized to GERRIT_YFPT: ${env.GERRIT_YFPT_URL} refs/for/${env.GERRIT_YFPT_BRANCH}"
        }

        failure {
            echo "===== Code Sync Failed ====="
            error("Code Sync Process Failed! Please check the build log for details.")
        }
    }
}