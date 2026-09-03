rootProject.name = "workflow-engine"

include(
    "workflow-core",
    "workflow-persistence-flyway",
    "workflow-persistence-jpa",
    "workflow-persistence-mybatis",
    "workflow-sample",
    "workflow-tests"
)