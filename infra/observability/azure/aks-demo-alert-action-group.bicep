@description('Name of the Azure Monitor Action Group for AKS demo backend alerts.')
param actionGroupName string = 'tcg-store-aks-demo-alerts'

@description('Short name shown by Azure Monitor notification channels. Must be 12 characters or fewer.')
@maxLength(12)
param groupShortName string = 'tcgobsdemo'

@description('Email recipients approved to receive AKS demo observability alerts.')
@minLength(1)
param alertEmailAddresses array

resource actionGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: actionGroupName
  location: 'global'
  properties: {
    groupShortName: groupShortName
    enabled: true
    emailReceivers: [
      for (emailAddress, index) in alertEmailAddresses: {
        name: 'tcg-store-alert-${index + 1}'
        emailAddress: emailAddress
        useCommonAlertSchema: true
      }
    ]
  }
}

output actionGroupResourceId string = actionGroup.id
