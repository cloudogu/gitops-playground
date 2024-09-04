<?xml version='1.1' encoding='UTF-8'?>
<jenkins.branch.OrganizationFolder plugin="branch-api@2.1178.v969d9eb_c728e">
    <actions/>
    <description></description>
    <properties>
        <jenkins.branch.OrganizationChildHealthMetricsProperty>
            <templates>
                <com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric plugin="cloudbees-folder@6.942.vb_43318a_156b_2">
                    <nonRecursive>false</nonRecursive>
                </com.cloudbees.hudson.plugins.folder.health.WorstChildHealthMetric>
            </templates>
        </jenkins.branch.OrganizationChildHealthMetricsProperty>
        <jenkins.branch.OrganizationChildOrphanedItemsProperty>
            <strategy class="jenkins.branch.OrganizationChildOrphanedItemsProperty$Inherit"/>
        </jenkins.branch.OrganizationChildOrphanedItemsProperty>
        <jenkins.branch.OrganizationChildTriggersProperty>
            <templates>
                <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin="cloudbees-folder@6.942.vb_43318a_156b_2">
                    <spec>H H/4 * * *</spec>
                    <interval>86400000</interval>
                </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
            </templates>
        </jenkins.branch.OrganizationChildTriggersProperty>
        <org.jenkinsci.plugins.docker.workflow.declarative.FolderConfig plugin="docker-workflow@1.25">
            <dockerLabel></dockerLabel>
            <registry plugin="docker-commons@443.v921729d5611d"/>
        </org.jenkinsci.plugins.docker.workflow.declarative.FolderConfig>
        <jenkins.branch.NoTriggerOrganizationFolderProperty>
            <branches>.*</branches>
            <strategy>NONE</strategy>
        </jenkins.branch.NoTriggerOrganizationFolderProperty>
    </properties>
    <folderViews class="jenkins.branch.OrganizationFolderViewHolder">
        <owner reference="../.."/>
    </folderViews>
    <healthMetrics/>
    <icon class="jenkins.branch.MetadataActionFolderIcon">
        <owner class="jenkins.branch.OrganizationFolder" reference="../.."/>
    </icon>
    <orphanedItemStrategy class="com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy" plugin="cloudbees-folder@6.942.vb_43318a_156b_2">
        <pruneDeadBranches>true</pruneDeadBranches>
        <daysToKeep>-1</daysToKeep>
        <numToKeep>-1</numToKeep>
        <abortBuilds>false</abortBuilds>
    </orphanedItemStrategy>
    <triggers>
        <com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger plugin="cloudbees-folder@6.942.vb_43318a_156b_2">
            <spec>H H/4 * * *</spec>
            <interval>86400000</interval>
        </com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger>
    </triggers>
    <disabled>false</disabled>
    <navigators>
        <com.cloudogu.scmmanager.scm.ScmManagerNavigator plugin="scm-manager@1.7.1">
            <serverUrl>${SCMM_NAMESPACE_JOB_SERVER_URL}</serverUrl>
            <namespace>${SCMM_NAMESPACE_JOB_NAMESPACE}</namespace>
            <credentialsId>${SCMM_NAMESPACE_JOB_CREDENTIALS_ID}</credentialsId>
            <dependencyChecker class="null"/>
            <traits>
                <com.cloudogu.scmmanager.scm.PullRequestDiscoveryTrait>
                    <excludeBranchesWithPRs>false</excludeBranchesWithPRs>
                </com.cloudogu.scmmanager.scm.PullRequestDiscoveryTrait>
                <com.cloudogu.scmmanager.scm.ScmManagerBranchDiscoveryTrait/>
            </traits>
            <apiFactory>
                <credentialsLookup/>
            </apiFactory>
        </com.cloudogu.scmmanager.scm.ScmManagerNavigator>
    </navigators>
    <projectFactories>
        <org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectFactory plugin="workflow-multibranch@795.ve0cb_1f45ca_9a_">
            <scriptPath>Jenkinsfile</scriptPath>
        </org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProjectFactory>
    </projectFactories>
    <buildStrategies/>
    <strategy class="jenkins.branch.DefaultBranchPropertyStrategy">
        <properties class="empty-list"/>
    </strategy>
</jenkins.branch.OrganizationFolder>