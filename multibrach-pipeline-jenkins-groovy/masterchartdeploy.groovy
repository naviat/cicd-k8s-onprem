def call() {
    def GETVERSION = '''
      VERSION=$(sed -n "/^current_version/{ s|.*= ||; p; }" ./.bumpversion.cfg)
      echo $VERSION
    '''

    pipeline {
        agent any

        options {
            timeout(time: 1, unit: 'HOURS')
        }
        environment {
            VERSION = sh(script: """${GETVERSION}""", returnStdout: true).trim()
            EMAIL_TO = sh(script: "cat ./cicd/jenkins/config.yaml | docker run -i eldhodoc/yq:latest:latest -r '.notifications[].email' 2>/dev/null | sed -r '/.+/{ s|^|, |; }' || true" , returnStdout: true)
        }

        stages {
            stage('Initial checks') {
                steps {
                    sh '''
                        echo "
                          VERSION = $VERSION
                        "
                        if ! ( [ "$BRANCH_NAME" = master ]
                            ); then
                          echo "Branch name $BRANCH_NAME or pull request ${CHANGE_BRANCH%/*}->${CHANGE_TARGET%/*} not supported."
                          exit 1
                        fi  
                    '''
                }
            }

            stage('Deploy') {
                when {
                    branch 'master'
                }
                steps {
                    sh '''
                    docker run --rm example.example.com/example/kubectl:with-helm-repos /bin/bash -c "
                    helm repo update
                    helm fetch harbor-qa/demomaster --version $VERSION
                    tar -xf demomaster-$VERSION.tgz
                    cd demomaster
                    helm dep update
                    cd ..
                    kubectl config use-context <your context>
                    helm tiller run helm delete --purge demomaster || true
                    helm tiller run helm upgrade --install demomaster demomaster --force --recreate-pods --namespace demomaster "
                    '''
                }
            }
        }

        post {
            failure {
                emailext attachLog: true, body: 'Check console output at $BUILD_URL to view the results. \n\n ${CHANGES} \n\n -------------------------------------------------- \n${BUILD_LOG, maxLines=100, escapeHtml=false}',
                to: env.commiteremail + env.EMAIL_TO,
                subject: 'Build failed in Jenkins: $PROJECT_NAME - #$BUILD_NUMBER'
            }
            unstable {
                emailext body: 'Check console output at $BUILD_URL to view the results. \n\n ${CHANGES} \n\n -------------------------------------------------- \n${BUILD_LOG, maxLines=100, escapeHtml=false}', 
                to: env.commiteremail + env.EMAIL_TO,
                subject: 'Unstable build in Jenkins: $PROJECT_NAME - #$BUILD_NUMBER'
            }
            success {
                emailext attachLog: true, body: 'Check console output at $BUILD_URL to view the results. \n\n ${CHANGES} \n\n -------------------------------------------------- \n${BUILD_LOG, maxLines=100, escapeHtml=false}',
                to: env.commiteremail + env.EMAIL_TO,
                subject: 'Build success in Jenkins: $PROJECT_NAME - #$BUILD_NUMBER'
            }
            always {
                cleanWs()
            }
        }
    }
}
