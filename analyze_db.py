import pymysql
from datetime import datetime
from collections import defaultdict

def connect_to_database():
    """Connect to MySQL database"""
    try:
        connection = pymysql.connect(
            host='127.0.0.1',
            port=3306,
            database='leetcode_tracker',
            user='root',
            password='changeme'
        )
        print("Successfully connected to MySQL database\n")
        return connection
    except Exception as e:
        print(f"Error connecting to MySQL: {e}")
        return None

def explore_schema(connection):
    """Explore database schema"""
    cursor = connection.cursor()
    
    print("=" * 80)
    print("DATABASE SCHEMA")
    print("=" * 80)
    
    # Get all tables
    cursor.execute("SHOW TABLES")
    tables = cursor.fetchall()
    
    for (table_name,) in tables:
        print(f"\nTable: {table_name}")
        print("-" * 80)
        
        # Get column information
        cursor.execute(f"DESCRIBE {table_name}")
        columns = cursor.fetchall()
        
        for column in columns:
            print(f"  {column[0]:20s} {column[1]:20s} {column[2]:10s} {column[3]:10s}")
        
        # Get row count
        cursor.execute(f"SELECT COUNT(*) FROM {table_name}")
        count = cursor.fetchone()[0]
        print(f"\n  Total rows: {count}")
    
    cursor.close()
    print("\n")

def analyze_submissions(connection):
    """Analyze submission data"""
    cursor = connection.cursor(pymysql.cursors.DictCursor)
    
    print("=" * 80)
    print("LEETCODE SUBMISSION ANALYSIS")
    print("=" * 80)
    
    # Total submissions
    cursor.execute("SELECT COUNT(*) as total FROM tracker_submission")
    total_submissions = cursor.fetchone()['total']
    print(f"\n1. TOTAL SUBMISSIONS: {total_submissions}")
    
    # Unique questions attempted
    cursor.execute("SELECT COUNT(DISTINCT question_id) as unique_questions FROM tracker_submission")
    unique_questions = cursor.fetchone()['unique_questions']
    print(f"\n2. UNIQUE QUESTIONS ATTEMPTED: {unique_questions}")
    
    # Accepted submissions (AC)
    cursor.execute("SELECT COUNT(*) as accepted FROM tracker_submission WHERE status = 'Accepted'")
    accepted_count = cursor.fetchone()['accepted']
    print(f"\n3. ACCEPTED SUBMISSIONS (AC): {accepted_count}")
    
    # Unique questions solved (AC)
    cursor.execute("SELECT COUNT(DISTINCT question_id) as solved FROM tracker_submission WHERE status = 'Accepted'")
    solved_questions = cursor.fetchone()['solved']
    print(f"\n4. UNIQUE QUESTIONS SOLVED (AC): {solved_questions}")
    
    # Success rate
    success_rate = (accepted_count / total_submissions * 100) if total_submissions > 0 else 0
    solve_rate = (solved_questions / unique_questions * 100) if unique_questions > 0 else 0
    print(f"\n5. SUCCESS RATES:")
    print(f"   - Submission success rate: {success_rate:.2f}%")
    print(f"   - Question solve rate: {solve_rate:.2f}%")
    
    # Status breakdown
    print(f"\n6. STATUS BREAKDOWN:")
    cursor.execute("""
        SELECT status, COUNT(*) as count, 
               ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM tracker_submission), 2) as percentage
        FROM tracker_submission 
        GROUP BY status 
        ORDER BY count DESC
    """)
    statuses = cursor.fetchall()
    for status in statuses:
        print(f"   - {status['status']:30s}: {status['count']:5d} ({status['percentage']:6.2f}%)")
    
    # Language distribution
    print(f"\n7. LANGUAGE DISTRIBUTION:")
    cursor.execute("""
        SELECT language, COUNT(*) as count,
               ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM tracker_submission), 2) as percentage
        FROM tracker_submission 
        GROUP BY language 
        ORDER BY count DESC
    """)
    languages = cursor.fetchall()
    for lang in languages:
        print(f"   - {lang['language']:20s}: {lang['count']:5d} ({lang['percentage']:6.2f}%)")
    
    # Top attempted questions
    print(f"\n8. TOP 10 ATTEMPTED QUESTIONS:")
    cursor.execute("""
        SELECT s.question_id, q.title, COUNT(*) as attempts,
               SUM(CASE WHEN s.status = 'Accepted' THEN 1 ELSE 0 END) as accepted
        FROM tracker_submission s
        LEFT JOIN tracker_question q ON s.question_id = q.id
        GROUP BY s.question_id, q.title
        ORDER BY attempts DESC
        LIMIT 10
    """)
    top_questions = cursor.fetchall()
    for i, q in enumerate(top_questions, 1):
        title = q['title'] if q['title'] else f"Question {q['question_id']}"
        title_display = title[:50] if len(title) > 50 else title
        print(f"   {i:2d}. {title_display:50s} (ID: {q['question_id']}) - {q['attempts']} attempts, {q['accepted']} AC")
    
    # Temporal analysis - submissions by date
    print(f"\n9. TEMPORAL TRENDS:")
    cursor.execute("""
        SELECT DATE(submitted_at) as date, COUNT(*) as submissions,
               SUM(CASE WHEN status = 'Accepted' THEN 1 ELSE 0 END) as accepted
        FROM tracker_submission 
        GROUP BY DATE(submitted_at)
        ORDER BY date DESC
        LIMIT 15
    """)
    temporal = cursor.fetchall()
    print(f"   Recent submission activity (last 15 days with activity):")
    for entry in temporal:
        date_str = str(entry['date']) if entry['date'] else 'Unknown'
        print(f"   - {date_str}: {entry['submissions']} submissions, {entry['accepted']} AC")
    
    # Most common errors
    print(f"\n10. NON-ACCEPTED STATUS BREAKDOWN:")
    cursor.execute("""
        SELECT status, COUNT(*) as count
        FROM tracker_submission 
        WHERE status != 'Accepted'
        GROUP BY status 
        ORDER BY count DESC
    """)
    errors = cursor.fetchall()
    for error in errors:
        print(f"   - {error['status']:30s}: {error['count']:5d}")
    
    # Questions with multiple attempts but not solved
    print(f"\n11. CHALLENGING QUESTIONS (3+ attempts, no AC):")
    cursor.execute("""
        SELECT s.question_id, q.title, COUNT(*) as attempts
        FROM tracker_submission s
        LEFT JOIN tracker_question q ON s.question_id = q.id
        WHERE s.question_id NOT IN (SELECT DISTINCT question_id FROM tracker_submission WHERE status = 'Accepted')
        GROUP BY s.question_id, q.title
        HAVING COUNT(*) >= 3
        ORDER BY attempts DESC
        LIMIT 10
    """)
    challenging = cursor.fetchall()
    if challenging:
        for q in challenging:
            title = q['title'] if q['title'] else f"Question {q['question_id']}"
            title_display = title[:50] if len(title) > 50 else title
            print(f"   - {title_display:50s} (ID: {q['question_id']}) - {q['attempts']} attempts")
    else:
        print(f"   None found (all questions with 3+ attempts have been solved!)")
    
    # Language-specific success rates
    print(f"\n12. SUCCESS RATE BY LANGUAGE:")
    cursor.execute("""
        SELECT language, 
               COUNT(*) as total,
               SUM(CASE WHEN status = 'Accepted' THEN 1 ELSE 0 END) as accepted,
               ROUND(SUM(CASE WHEN status = 'Accepted' THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) as success_rate
        FROM tracker_submission 
        GROUP BY language 
        ORDER BY total DESC
    """)
    lang_success = cursor.fetchall()
    for lang in lang_success:
        print(f"   - {lang['language']:20s}: {lang['accepted']}/{lang['total']} ({lang['success_rate']:.2f}%)")
    
    # Question difficulty breakdown
    print(f"\n13. QUESTIONS BY DIFFICULTY:")
    cursor.execute("""
        SELECT q.difficulty, COUNT(DISTINCT s.question_id) as attempted,
               SUM(CASE WHEN s.status = 'Accepted' THEN 1 ELSE 0 END) as solved_submissions
        FROM tracker_submission s
        LEFT JOIN tracker_question q ON s.question_id = q.id
        WHERE q.difficulty IS NOT NULL
        GROUP BY q.difficulty
        ORDER BY 
            CASE q.difficulty 
                WHEN 'Easy' THEN 1 
                WHEN 'Medium' THEN 2 
                WHEN 'Hard' THEN 3 
            END
    """)
    difficulties = cursor.fetchall()
    for diff in difficulties:
        print(f"   - {diff['difficulty']:10s}: {diff['attempted']:3d} questions attempted")
    
    # Performance metrics
    print(f"\n14. PERFORMANCE METRICS:")
    cursor.execute("""
        SELECT 
            AVG(passed_tests * 1.0 / NULLIF(total_tests, 0)) * 100 as avg_test_pass_rate,
            COUNT(CASE WHEN passed_tests = total_tests THEN 1 END) as all_tests_passed
        FROM tracker_submission
        WHERE total_tests IS NOT NULL AND total_tests > 0
    """)
    perf = cursor.fetchone()
    if perf and perf['avg_test_pass_rate']:
        print(f"   - Average test pass rate: {perf['avg_test_pass_rate']:.2f}%")
        print(f"   - Submissions passing all tests: {perf['all_tests_passed']}")
    
    cursor.close()

def main():
    connection = connect_to_database()
    
    if connection:
        try:
            explore_schema(connection)
            analyze_submissions(connection)
            print("\n" + "=" * 80)
            print("Analysis complete!")
            print("=" * 80)
        except Exception as e:
            print(f"Error during analysis: {e}")
        finally:
            connection.close()
            print("\nDatabase connection closed.")

if __name__ == "__main__":
    main()
