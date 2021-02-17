def call() {
    def SUPPORT_BRANCH = ( BRANCH_NAME ==~ /^support\/.*/ || env.CHANGE_TARGET ==~ /^support\/.*/ || env.CHANGE_BRANCH ==~ /^support\/.*/ )
    def MASTER_BRANCH = ( BRANCH_NAME == "master" || env.CHANGE_TARGET == "master" )
    def NODE_VERSION = '10.15.3';
    def GETVERSION = '''
      VERSION=$(./cicd/jenkins/version.sh)
      if [ "$CHANGE_BRANCH" = "" ]; then
        TAG_SUFFIX="-$BRANCH_NAME-build$BUILD_NUMBER"
      else
        TAG_SUFFIX="-$CHANGE_BRANCH-build$BUILD_NUMBER"
      fi
      CREATE_RELEASE=no
      if ( [ "$BRANCH_NAME" = develop ] || [ "${BRANCH_NAME:0:8}" = support/ ] ) &&
         [ $(git tag | grep -Fcx ${VERSION}) = 0 ] && [ $(echo "$VERSION" | grep -ci 'snapshot$') = 0 ]; then
        CREATE_RELEASE=yes
        TAG_SUFFIX=""
      fi
      printf %s "$CREATE_RELEASE"  > jenkins_CREATE_RELEASE
      printf %s "$TAG_SUFFIX" | sed "s|[/_]|-|g" | sed "s|[%*$^&()]|-|g" | head -c 45 | sed "s|-$||" > jenkins_TAG_SUFFIX
      echo $VERSION
    '''

    pipeline {
        agent any

        tools {
            jdk 'jdk12'
        }

        options {
            timeout(time: 1, unit: 'HOURS')
        }

        parameters {
            string(name: 'DOCKER_FULL_BUILD', defaultValue: 'false', description: 'Whether to build all layers of docker images, i.e. docker build --no-cache.')
        }

        environment {
            VERSION = sh(script: """${GETVERSION}""", returnStdout: true).trim()
            TAG_SUFFIX = readFile('jenkins_TAG_SUFFIX')
            CREATE_RELEASE = readFile('jenkins_CREATE_RELEASE')
            SERVICES = sh(script: "find cicd/helm -mindepth 1 -maxdepth 1 -type d | sed 's|.*/||'", returnStdout: true)
            commitId = sh(returnStdout: true, script: 'git rev-parse HEAD')
            commitcheck = sh(returnStdout: true, script: '[ "$CHANGE_BRANCH" = "" ] || git rev-parse origin/$CHANGE_BRANCH')
            result = sh(returnStdout: true, script: 'git log --format=%B HEAD -n1')
            author = sh(returnStdout: true, script: 'git log --pretty=%an HEAD -n1')
            commitmsg = sh(returnStdout: true, script: 'git log --oneline -n 1 HEAD')
            projectname = sh(script: 'git remote get-url origin | cut -d "/" -f5', returnStdout: true)
            commiteremail = sh(returnStdout: true, script: 'git log --pretty=%ae HEAD -n1')
            jenkinsurl = sh(script: 'echo "${BUILD_URL}"' , returnStdout: true).trim()
            EMAIL_TO = sh(script: "cat ./cicd/jenkins/config.yaml | docker run -i eldhodoc/yq:latest -r '.notifications[].email' 2>/dev/null | sed -r '/.+/{ s|^|, |; }' || true" , returnStdout: true)
        }

        stages {
            stage('Initial checks') {
                steps {
                    sh '''
                        echo "
                          BRANCH_NAME = $BRANCH_NAME
                          CHANGE_BRANCH = $CHANGE_BRANCH
                          CHANGE_TARGET = $CHANGE_TARGET
                          TAG_SUFFIX = $TAG_SUFFIX
                          VERSION = $VERSION
                          COMMITID = $commitId
                        "

                        if ! ( [ "$BRANCH_NAME" = master ] ||
                               [ "$BRANCH_NAME" = develop ] ||
                               [ "${BRANCH_NAME:0:8}" = support/ ] ||
                               ( [ "$CHANGE_BRANCH" = develop ]        && [ "$CHANGE_TARGET" = master ] ) ||
                               ( [ "${CHANGE_BRANCH:0:8}" = support/ ] && [ "$CHANGE_TARGET" = develop ] ) ||
                               ( [ "${CHANGE_BRANCH:0:7}" = bugfix/ ]  && [ "$CHANGE_TARGET" = develop ] ) ||
                               ( [ "${CHANGE_BRANCH:0:8}" = feature/ ] && [ "$CHANGE_TARGET" = develop ] ) ||
                               ( [ "${CHANGE_BRANCH:0:7}" = bugfix/ ]  && [ "${CHANGE_TARGET:0:8}" = support/ ] ) ||
                               ( [ "${CHANGE_BRANCH:0:8}" = feature/ ] && [ "${CHANGE_TARGET:0:8}" = support/ ] )
                            ); then
                          echo "Branch name $BRANCH_NAME or pull request ${CHANGE_BRANCH%/*}->${CHANGE_TARGET%/*} not supported."
                          exit 1
                        elif [ "$CHANGE_TARGET" = master ] && [ "$CHANGE_BRANCH" != develop ]; then
                          echo "Error: merges into master can only come from the develop branch."
                          exit 1
                        elif [ "$CHANGE_TARGET" = master ] &&
                             [ "$(git describe --tags --exact-match $commitcheck 2>/dev/null)" != "$VERSION" ]; then
                          echo "Error: merges into master require a git tag to exists, it does not for commit ${commitcheck}."
                          exit 1
                        fi
                    '''
                }
            }

            stage('Build') {
                when {
                    allOf {
                        not { expression { MASTER_BRANCH } }
                        expression { fileExists('./cicd/jenkins/stage_build.sh') }
                    }
                }
                steps {
                    sh './cicd/jenkins/stage_build.sh'
                }
            }

            stage('Run unit tests') {
                when {
                    not { expression { MASTER_BRANCH } }
                }
                steps {
                    nvm(version: NODE_VERSION) {
                        sh './cicd/jenkins/stage_test.sh --full_build "$DOCKER_FULL_BUILD"'
                    }
                }
            }

            stage('Run quality checks') {
                when {
                    allOf {
                        branch 'develop'
                    }
                }
                environment {
                    scannerHome = tool 'SonarQubeScanner'
                }
                steps {
                    withSonarQubeEnv('sonarstg') {
                        sh '${scannerHome}/bin/sonar-scanner -Dproject.settings=cicd/sonar-project.properties -Dsonar.projectVersion=$VERSION$TAG_SUFFIX'
                    }
                }
            }

            stage ("SonarQube Quality Gate") {
                when {
                    allOf {
                        branch 'develop'
                    }
                }
                steps {
                    script {
                        def qualitygate = waitForQualityGate() 
                        if (qualitygate.status != "OK") {
                            error "Pipeline aborted due to quality gate coverage failure: ${qualitygate.status}"
                        }
                    }
                }
            }

            stage('Build and push docker image') {
                when {
                    allOf {
                        not { expression { MASTER_BRANCH } }
                        expression { fileExists('./cicd/jenkins/stage_docker.sh') }
                    }
                }
                steps {
                    sh '''
                        PREFIX="packages-pr.internal.example.com/example/"
                        if [ "$BRANCH_NAME" = develop ] || [ "${BRANCH_NAME:0:8}" = support/ ]; then
                          PREFIX="packages-stg.internal.example.com/example/"
                        fi
                        IMAGES=$(./cicd/jenkins/stage_docker.sh --full_build "$DOCKER_FULL_BUILD" --prefix "$PREFIX" --suffix "$TAG_SUFFIX")
                        echo $IMAGES > jenkins_DOCKER_IMAGES
                        echo "stage_docker.sh built the following images:"
                        cat jenkins_DOCKER_IMAGES
                        for image in $IMAGES; do
                          docker push $image;
                        done
                    '''
                }
            }

            stage('Push helm chart') {
                when {
                    not { expression { MASTER_BRANCH } }
                }
                steps {
                    sh '''
                        HELM_REPO="example-pr" #Configure in jenkins server with respecrtive name 
                        if [ "$BRANCH_NAME" = develop ] || [ "${BRANCH_NAME:0:8}" = support/ ]; then
                          HELM_REPO="example-stg" #Configure in jenkins server with respecrtive name 
                        fi
                        git clone https://github.com/eldhodevops/service-template.git
                        for HELM_CHART in $SERVICES; do
                          rsync -av service-template/helm-template/templates ./cicd/helm/$HELM_CHART/
                          sed -i "s|^version: .*|version: $VERSION$TAG_SUFFIX|" ./cicd/helm/$HELM_CHART/Chart.yaml
                          helm push ./cicd/helm/$HELM_CHART "$HELM_REPO"
                        done
                    '''
                }
            }

            stage('Master chart update') {
                when {
                    branch 'master'
                }
                steps {
                    script {
                        masterChartUpdate()
                    }
                }
            }

            stage('Deploy') {
                when {
                    allOf {
                        not { expression { SUPPORT_BRANCH } }
                        not { expression { MASTER_BRANCH } }
                    }
                }
                steps {
                    script {
                        deployUsingYamlConfig()
                    }
                }
            }

            stage('Create release') {
                when {
                    allOf {
                        expression { env.BRANCH_NAME == "develop" || BRANCH_NAME ==~ /^support\/.*/ }
                        expression { env.CREATE_RELEASE == "yes" }
                    }
                }
                steps {
                    withCredentials([usernamePassword(credentialsId: 'opsadmin', passwordVariable: 'GIT_PASSWORD', usernameVariable: 'GIT_USERNAME')]) {
                        sh '''
                            if [ -f "./cicd/jenkins/stage_docker.sh" ]; then
                              for image in $(cat jenkins_DOCKER_IMAGES); do
                                releasetag=$(echo $image | cut -d "/" -f 3 | cut -d : -f1)
                                docker tag $image packages-qa.example.com/example/$releasetag:$VERSION
                                docker push packages-qa.example.com/example/$releasetag:$VERSION
                              done
                            fi
                            rm -rf helm-template
                            git clone https://github.com/eldhodevops/service-template.git
                            for HELM_CHART in $SERVICES; do
                              rsync -av service-template/helm-template/templates ./cicd/helm/$HELM_CHART/
                              sed -i "s|^version: .*|version: $VERSION|" ./cicd/helm/$HELM_CHART/Chart.yaml
                              helm push ./cicd/helm/$HELM_CHART example-qa
                            done
                            if [ -f "./cicd/jenkins/stage_release.sh" ]; then
                              ./cicd/jenkins/stage_release.sh
                            fi
                            git tag ${VERSION}
                            git push --tags $(echo $GIT_URL | sed "s|^https://|https://$GIT_USERNAME:$GIT_PASSWORD@|")
                        '''
                    }
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
