"""Shared gate for local JVM integration scripts, before any server starts."""
import re

_PATTERN = re.compile(r"jdbc:postgresql://(?:localhost|127\.0\.0\.1|\[::1\])(?::([0-9]+))?/rufit_test\Z")


def test_environment(environment):
    url = environment.get('DB_URL', '')
    match = _PATTERN.fullmatch(url)
    if (not match or (match.group(1) and not 1 <= int(match.group(1)) <= 65535)
            or environment.get('DB_USERNAME') != 'rufit_test' or not environment.get('DB_PASSWORD')):
        raise ValueError('Test DB guard: use a loopback PostgreSQL database and role named rufit_test with DB_PASSWORD.')
    # Inherit normal process settings, but do not let unrelated Spring/JVM overrides redirect the test.
    return {key: value for key, value in environment.items()
            if not key.startswith('SPRING_') and key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
