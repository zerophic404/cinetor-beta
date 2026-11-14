# Decisions log (autonomous mode) — read with newest handoff
- 2026-11-14 User: "do everything yourself, give final output". No user input from now. Decide + log here.
- Hosting: private source; built PWA → public repo Ashrix404/cinetor-app gh-pages → https://ashrix404.github.io/cinetor-app/ ; BASE_PATH=/cinetor-app ; secret PAGES_DEPLOY_KEY ; deploy via peaceiris/actions-gh-pages@v4 (deploy_key, external_repository, publish_branch gh-pages, publish_dir web/build). Replace old deploy-pages job.
- Signing: PKCS12 keystore; secrets CINETOR_KEYSTORE_B64/CINETOR_KEYSTORE_PASS/CINETOR_KEY_PASS/CINETOR_KEY_ALIAS=cinetor; storeType pkcs12. Wire into release AND debug-installable builds early so updates install over old.
- QA: no real device → CI emulator smoke tests + screenshots each phase from Ph2; review with vision.
- Video sources: keep mock test streams.
