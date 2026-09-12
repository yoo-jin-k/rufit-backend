import unittest
from test_database import test_environment

class TestDatabaseGuardTests(unittest.TestCase):
    def test_valid_environment_removes_unrelated_spring_and_jvm_overrides(self):
        env={'DB_URL':'jdbc:postgresql://localhost:5435/rufit_test','DB_USERNAME':'rufit_test','DB_PASSWORD':'test-only',
             'SPRING_FLYWAY_URL':'not-used','SPRING_APPLICATION_JSON':'not-used','JAVA_TOOL_OPTIONS':'not-used','JAVA_HOME':'kept'}
        result=test_environment(env)
        self.assertEqual(result['JAVA_HOME'],'kept')
        self.assertEqual(result['DB_URL'],env['DB_URL'])
        self.assertNotIn('SPRING_FLYWAY_URL',result)
        self.assertNotIn('SPRING_APPLICATION_JSON',result)
        self.assertNotIn('JAVA_TOOL_OPTIONS',result)
    def test_rejects_wrong_database_role_remote_host_and_url_overrides(self):
        for url in ['jdbc:postgresql://localhost/rufit','jdbc:postgresql://remote.test/rufit_test',
                    'jdbc:postgresql://localhost/rufit_test?user=other','jdbc:postgresql://localhost:0/rufit_test',
                    'jdbc:postgresql://localhost:65536/rufit_test']:
            with self.assertRaises(ValueError):
                test_environment({'DB_URL':url,'DB_USERNAME':'rufit_test','DB_PASSWORD':'test-only'})
        with self.assertRaises(ValueError):
            test_environment({'DB_URL':'jdbc:postgresql://localhost/rufit_test','DB_USERNAME':'rufit','DB_PASSWORD':'test-only'})
if __name__=='__main__': unittest.main()
