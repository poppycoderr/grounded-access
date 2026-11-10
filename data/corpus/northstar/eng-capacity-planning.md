# Capacity Planning

This fictional procedure is part of the Northstar Cloud engineering handbook.

## Quarterly review

Each team reviews the capacity of its services every quarter, using the peak load of the past three months.

## Headroom

Services are sized so that they can carry twice their peak load. A service below that headroom gets capacity before any new feature work.

## Seasonal peaks

Billing services receive extra capacity in the last three days of each month, when most invoices are created.

## Forecasts

Forecasts for the next quarter are built from the growth of active customer accounts, not from revenue, because revenue grows faster than load when customers move to larger plans. The platform team publishes the account growth forecast in the first week of each quarter, and every team uses the same number. A team that expects a launch, a large customer or a new heavy feature adds its own estimate on top and writes down where the estimate came from. After the quarter the forecast is compared with what actually happened, and the difference is recorded so that next quarter's forecast can be corrected. Teams whose forecast was more than 25 percent too low two quarters in a row review their method with the platform team.
