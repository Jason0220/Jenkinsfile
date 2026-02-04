pipeline {
    agent { label 'jenkins-node-10.10.192.18' }

    triggers {
        cron('0 1 * * *')
    }

    environment {
        REPO_URL = 'ssh://jeremy.li@10.10.192.13:29418/BES2800BP_GLASS'
        BRANCH = 'lily_submit,bes_base'
        UPSTREAM_REMOTE = 'upstream'
        ORIGIN_REMOTE = 'origin'
    }

    stages {
        stage('Env & fetchCode') {
            steps {
                script {
                    cleanWs()

                    git url: env.REPO_URL,
                        credentialsId: 'Jenkins',
                        branch: 'master'

                    sh """
                        git remote add ${UPSTREAM_REMOTE} ssh://jeremy.li@yfpt.goertek.com:29418/BES2800BP_GLASS || git remote set-url ${UPSTREAM_REMOTE} ssh://jeremy.li@yfpt.goertek.com:29418/BES2800BP_GLASS
                        git remote set-url ${ORIGIN_REMOTE} ${REPO_URL}
                    """
                }
            }
        }

        stage('Sync') {
            steps {
                script {
                    def branch = env.BRANCH.split(',')
                    // Iterate through all branches
                    branch.each { branchName ->
                        syncBranch(branchName)
                    }
                }
            }
        }
    }

    post {
        success {
            echo "✅ ${env.BRANCH.join(', ')} branch sync completed successfully, with upstream code as the source of truth pushed to origin."
        }
        failure {
            echo "❌ Branch sync failed. Please check the logs to troubleshoot the issue."
            emailext(
            to: 'jeremy.li@goertek.com',
            subject: "[Jenkins Build Failed] BES2800BP_GLASS Repository Sync Task #${BUILD_NUMBER}",
            body: """
                <h3>BES2800BP_GLASS Repository Sync Failed</h3>
                <p><strong>JOB_NAME:</strong> ${JOB_NAME}</p>
                <p><strong>BUILD_NUMBER:</strong> #${BUILD_NUMBER}</p>
                <p><strong>Failure Time:</strong> ${BUILD_TIMESTAMP}</p>
                <p><strong>Failure Reason:</strong> 请查看 Jenkins 构建日志排查问题</p>
                <p><strong>Console Log:</strong> <a href="${BUILD_URL}console">${BUILD_URL}console</a></p>
                <p><strong>Branch: </strong> lily_submit, bes_base</p>
                <p><strong>Core Workflow:</strong> The process of syncing code from the upstream remote to the origin remote has failed</p>
            """,
            mimeType: 'text/html',
            )
        }
        always {
            cleanWs()
        }
    }
}

def syncBranch(String branchName) {
    script {
        echo "Sync begin：${branchName}"
        sh """
            git fetch ${UPSTREAM_REMOTE} ${branchName}:${branchName} --force
            git checkout ${branchName} || git checkout -b ${branchName}
            git reset --hard ${UPSTREAM_REMOTE}/${branchName}
            git push ${ORIGIN_REMOTE} ${branchName} --force
        """
        echo "Branch ${branchName} synchronization completed!"
    }
}