# Revenue Leakage Reconciliation

Finds out, order by order, whether a D2C brand was paid the right amount by its marketplaces, quick-commerce apps and couriers. Anything short-paid, unpaid past due or overcharged lands in a claims list with evidence.

Built for Company A (Bengaluru, home textiles, about ₹43 crore a year across a website, 3 marketplaces and 2 quick-commerce apps).


## The problem

Finance downloads settlement, COD remittance and freight reports every month and matches them by hand in Excel. About 1.5% of order value leaks through unremitted COD, wrong fees, wrong weight slabs and lost returns, and month-end close takes 12 days.

## What it does

- Upload order, settlement, COD and freight files (CSV or Excel). The column mapping is saved per source.
- Matches every order and sets a status: settled, short-paid, unpaid past due, freight overcharged, return not received.
- Computes the expected amount from rate cards per channel and courier, and flags gaps over ₹10.
- Keeps GST TCS and income-tax TDS in separate columns, so tax deductions are never counted as leakage.
- Claims list by partner with evidence export.
- Money-at-risk dashboard by partner and age.
- Two roles: finance executive (upload, mappings, claims) and co-founder (dashboard and claims, read-only).

## How AI is used

Matching and expected amounts are rules, not AI. AI is used in two places, and a person approves both:

- **Column mapping:** when a partner changes its file format, an LLM suggests a mapping. You see a 20-row preview, approve it before saving, and every save is a new version you can roll back.
- **Missing order IDs:** suggested matches go to a review queue and are never closed automatically.

Customer names, phones and addresses are not stored or sent to the LLM (DPDP Act 2023).

## Tech stack

Spring Boot (Java), PostgreSQL, HTML/CSS/JavaScript served by Spring Boot. Matching is done with set-based SQL after a bulk load, to process 1 lakh rows in under a minute.

## Quick start

```bash
git clone https://github.com/maniksam08/Revenue_Leakage.git
cd Revenue_Leakage
# start PostgreSQL and set the environment variables, then:
mvn spring-boot:run
```

Open http://localhost:8080.


## Scope

Included: upload with saved mapping, order-level matching, rate-card expected amounts, claims list, money-at-risk view, seeded sample data.

Not included: returns tracker, TCS/TDS summary page, Tally export, automatic claim filing, storefront or inventory sync, GST filing.

## Author

Pratham Arora
