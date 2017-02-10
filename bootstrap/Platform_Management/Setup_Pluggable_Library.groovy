// Constants
def pluggableGitURL = "https://innersource.accenture.com/scm/adop-e/adop-pluggable-scm.git"

def platformManagementFolderName= "/Platform_Management"
def platformManagementFolder = folder(platformManagementFolderName) { displayName('Platform Management') }

// Jobs
def setupPluggable = freeStyleJob(platformManagementFolderName + "/Setup_Pluggable_Library")

// Setup setup_cartridge
setupPluggable.with {
    environmentVariables {
        keepBuildVariables(true)
        keepSystemVariables(true)
    }
    wrappers {
        preBuildCleanup()
    }
    steps {
        shell('''
            #!/bin/bash -ex
            mkdir -p $PLUGGABLE_SCM_PROVIDER_PROPERTIES_PATH $PLUGGABLE_SCM_PROVIDER_PATH
            mkdir -p ${PLUGGABLE_SCM_PROVIDER_PROPERTIES_PATH}/CartridgeLoader ${PLUGGABLE_SCM_PROVIDER_PROPERTIES_PATH}/ScmProviders

            echo "Extracting Pluggable library to additonal classpath location: ${PLUGGABLE_SCM_PROVIDER_PATH}"
            cp -r src/main/groovy/pluggable/ ${PLUGGABLE_SCM_PROVIDER_PATH}
            echo "******************"

            echo "Library contents: "
            ls ${PLUGGABLE_SCM_PROVIDER_PATH}pluggable/scm/
        ''')
    }
    scm {
        git {
            remote {
                name("origin")
                url("${pluggableGitURL}")
            }
            branch("*/feature/ADOPP-344")
        }
    }
}