def call() {
    config_yaml = readYaml(file: './cicd/jenkins/config.yaml')
    for(int i = 0; i < config_yaml.deployments.size(); i++) {
        service = config_yaml.deployments[i]
        env.SERVICE_NAME = service.name
        for(int j = 0; j < service.environments.size(); j++) {
            environment = service.environments[j]
            if(environment.name != 'stg') {
                continue
            }
            for(int k = 0; k < environment.targets.size(); k++) {
                target = environment.targets[k]
                env.TARGET_NAME = target.name
                sh '''
                    e='"'
                    microsvc=$SERVICE_NAME
                    MASTER_CHARTS_REPO="dummy-master-chart"
                    rm -rf $MASTER_CHARTS_REPO
                    git clone https://github.com/eldhodevops/$MASTER_CHARTS_REPO.git
                    cd $MASTER_CHARTS_REPO
                    bumpversion patch
                    cd demomaster
                    diff=`cat requirements.yaml | grep $microsvc || echo ""`
                    if [ -z "${diff}" ]; then
                        echo "- name: $microsvc" >> requirements.yaml
                        echo "  version: $VERSION" >> requirements.yaml
                        echo "  repository: $e"@example-qa$e"" >> requirements.yaml
                    else
                        mastervalue=`cat -n requirements.yaml | grep -A 1 $microsvc | grep version | awk '{print $1" "$3}'`
                        masterversion=`echo $mastervalue | awk '{print $2}'`
                        linenum=`echo $mastervalue | awk '{print $1}'`
                        latestversion=$VERSION
                        sed -i "${linenum}s/$masterversion/$VERSION/" requirements.yaml
                    fi
                    cd ..
                    git add .
                    git commit -m "${microsvc}" || true
                    git push
                    newversion=`sed -n "/^current_version/{ s|.*= ||; p; }" .bumpversion.cfg`
                    git tag ${newversion} $(git rev-list -1 HEAD)
                    git push origin --tags
                    helm push demomaster example-qa
                    cd ..
                '''
            }
        }
    }
}
