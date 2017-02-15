// Constants
def platformToolsGitURL = "https://github.com/IrmantasM/adop-platform-management.git"


// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def workspaceFolder = folder(workspaceFolderName)

def projectManagementFolderName= workspaceFolderName + "/Project_Management"
def projectManagementFolder = folder(projectManagementFolderName) { displayName('Project Management') }

// Jobs
def generateProjectJob = freeStyleJob(projectManagementFolderName + "/Generate_Project")

// Setup Generate_Project
generateProjectJob.with {
    parameters
    {
        stringParam("PROJECT_NAME","","The name of the project to be generated.")
        booleanParam('CUSTOM_SCM_NAMESPACE', false, 'Enables the option to provide a custom project namespace for your SCM provider')
        stringParam("ADMIN_USERS","","The list of users' email addresses that should be setup initially as admin. They will have full access to all jobs within the project.")
        stringParam("DEVELOPER_USERS","","The list of users' email addresses that should be setup initially as developers. They will have full access to all non-admin jobs within the project.")
        stringParam("VIEWER_USERS","","The list of users' email addresses that should be setup initially as viewers. They will have read-only access to all non-admin jobs within the project.")
    }
    environmentVariables
    {
        env('WORKSPACE_NAME',workspaceFolderName)
    }
    wrappers
    {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        if("${ADOP_LDAP_ENABLED}" == "true")
        {
            environmentVariables
            {
                env('DC', "${LDAP_ROOTDN}")
                env('OU_GROUPS','ou=groups')
                env('OU_PEOPLE','ou=people')
                env('OUTPUT_FILE','output.ldif')
            }

            credentialsBinding
            {
                usernamePassword("LDAP_ADMIN_USER", "LDAP_ADMIN_PASSWORD", "adop-ldap-admin")
            }
            sshAgent("adop-jenkins-master")
            
        }
    }
    steps
    {
        shell('''#!/bin/bash
                # Validate Variables
                pattern=" |'"
                if [[ "${PROJECT_NAME}" =~ ${pattern} ]]; then
                    echo "PROJECT_NAME contains a space, please replace with an underscore - exiting..."
                    exit 1
                fi''')
        conditionalSteps
        {
            condition
            {
                stringsMatch('${ADOP_ACL_ENABLED}', 'true', true)
            }
            runner('Fail')
            steps {
                systemGroovyScriptFile('${WORKSPACE}/workspaces/groovy/acl_admin.groovy')
                systemGroovyScriptFile('${WORKSPACE}/workspaces/groovy/acl_developer.groovy')
                systemGroovyScriptFile('${WORKSPACE}/workspaces/groovy/acl_viewer.groovy')
            }
        }
		conditionalSteps
        {
            condition
            {
			    stringsMatch('${ADOP_LDAP_ENABLED}', 'true', true)
            }
            runner('Fail')
            steps {
			    systemGroovyScriptFile('${WORKSPACE}/workspaces/groovy/ldap_roles.groovy')
            }
        }

        dsl
        {
            external("workspaces/jobs/**/*.groovy")
        }
    }
    scm
    {
        git
        {
            remote
            {
                name("origin")
                url("${platformToolsGitURL}")
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
}
