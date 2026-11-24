# Clarification needed - run ambiguous

The requirement is ambiguous in ways that change what gets built. Answer by creating a JSON file like `answers-template.json` and run:

```
./agentic resume ambiguous --answers <file>
```

## AMB-safety: What does 'safer' mean for this change - which threat should it address?

Triggered by: "safer"

- `denylist`: Protect people who click: block links to known-malicious destination domains (configurable denylist) *(suggested default)*
- `api-keys`: Protect the service: only authenticated clients (API keys) may create links

## AMB-insight: Which additional insight does marketing need?

Triggered by: "better insight"

- `referrers`: Traffic sources and daily trends (referrers, clicks per day) *(suggested default)*
- `geo`: Where clicks come from (per-country breakdown; requires processing client IP addresses)

