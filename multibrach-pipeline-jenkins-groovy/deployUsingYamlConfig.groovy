def call() {
    config_yaml = readYaml(file: './cicd/jenkins/config.yaml')
    if(!config_yaml.deployments)
        return
    for(int i = 0; i < config_yaml.deployments.size(); i++) {
        service = config_yaml.deployments[i]
        env.SERVICE_NAME = service.name
        for(int j = 0; j < service.environments.size(); j++) {
            environment = service.environments[j]
            env.ENVIRONMENTNAME = environment.name
            if(environment.name == 'stg' && env.BRANCH_NAME != 'develop') {
               continue;
            }
            if(environment.name == 'pr' && (!env.CHANGE_TARGET || env.CHANGE_TARGET != 'develop') ) {
              continue;
            }
            for(int k = 0; k < environment.targets.size(); k++) {
                target = environment.targets[k]
                env.TARGET_NAME = target.name
                env.EXTRA_ARGS = ''
                if(target.extra_args) {
                   env.EXTRA_ARGS = target.extra_args
                }
                sh '''
                    REPO=""
                    if ( [ "${CHANGE_BRANCH:0:8}" = feature/ ] || [ "${CHANGE_BRANCH:0:7}" = bugfix/ ] ) &&
                      [ "${CHANGE_TARGET}" = develop ]; then
                      CONTEXT="$TARGET_NAME-pr-1"
                      PODS="example-pr/$SERVICE_NAME"
                      ENV="pr"
                      REPO="packages-pr.internal.example.com/example"
                      CHART_VERSION="$VERSION$TAG_SUFFIX"
                    elif [ "$BRANCH_NAME" = develop ]; then
                      CONTEXT="$TARGET_NAME-stg"
                      PODS="example-stg/$SERVICE_NAME"
                      ENV="stg"
                      REPO="packages-stg.internal.example.com/example"
                      CHART_VERSION="$VERSION$TAG_SUFFIX"
                    fi
                    if [ "$REPO" != "" ]; then
                      docker run --rm example.example.com/example/kubectl:with-helm-repos /bin/bash -c "
                      helm repo update
                      kubectl config use-context $CONTEXT
                      helm tiller run helm delete --purge $SERVICE_NAME || true
                      helm tiller run helm upgrade \
                        $EXTRA_ARGS \
                        --install $SERVICE_NAME \
                        --recreate-pods $PODS \
                        --version $CHART_VERSION \
                        --set CUSTOMERNAME=example \
                        --set ENV=$ENV \
                        --namespace $TARGET_NAME \
                        --set image.onpremiserepository=$REPO "
                    else
                      echo "Error: branch $BRANCH_NAME or pull request ${CHANGE_BRANCH%/*}->${CHANGE_TARGET%/*} does not require deployment."
                      exit 1
                    fi
                   '''
            }
        }
    }
}
        
