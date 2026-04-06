# Contributors

Thank you to everyone who has contributed to this project.

## Authors

### Sungho Maeung
- **Role:** Author & Architect
- **GitHub:** [@smaeung](https://github.com/smaeung)
- **Contributions:**
  - Designed and implemented the five high-performance patterns (Ring Buffer, Actor Model, Mutex/Semaphore, Compose optimisations, MVI)
  - Defined the overall project architecture and data flow
  - Wrote the MVI layer (`TransactionIntent`, `TransactionState`, `TransactionViewModel`)
  - Designed the Compose UI layer (`TransactionScreen`, `TransactionItem`, `StatsBar`)
  - Authored the README and inline source documentation

### Claude Sonnet 4.6 (AI Assistant)
- **Role:** Implementation Partner
- **Model:** `claude-sonnet-4-6` by Anthropic
- **Contributions:**
  - Scaffolded the Android project build system (Gradle, version catalog)
  - Implemented all source files based on the architectural direction above
  - Authored the unit test suite (17 tests across 4 classes)
  - Performed Business Agent verification (4.5/5 pattern compliance score)
  - Performed Developer Agent QA iterations (fixed 4 BLOCKERs, 3 HIGH issues)
  - Performed QA Agent sign-off review (all issues resolved before merge)
  - Wrote inline comments explaining *why* each pattern decision was made

---

## How to Contribute

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/your-feature`
3. Make your changes with clear commit messages
4. Ensure all unit tests pass: `./gradlew :app:testDebugUnitTest`
5. Open a pull request against `main`

---

## Acknowledgements

This project demonstrates patterns from the Kotlin Coroutines documentation
and the Jetpack Compose performance guides. The multi-agent review process
(Business Agent → Developer Agent → QA Agent iteration) ensured every pattern
is correctly implemented, tested, and documented.
