# Just write some variable to smoke test templating
namePrefix: ${config.application.namePrefix}
<#if config.content.variables.someapp??>
myvar: ${config.content.variables.someapp.somevalue}
</#if>