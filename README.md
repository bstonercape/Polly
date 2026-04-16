# Polly

![Polly Logo](logo.png)

Polly is a fork of Signal that allows you to use multiple accounts/phone numbers.

This is a hackathon project, not intended for anything beyond demo use.

I had Claude consider several different approaches and do the architecture design for them [here](docs/multi-account-model.md).

We implemented Option 1 first, which basically just switches between isolated instances of the app.

Then to add support for backgorund notifications across any account, we implemented the concept of [background receivers](docs/background-receivers-plan.md).

Some other interesting explorations:
* [How to implement Molly-like encrypted database feature](docs/molly-encrypted-databse.md)
* [How to implement Org membership enforced groups using SSO](docs/org-enforced-groups.md)